package com.techtaurant.mainserver.security.jwt

import com.techtaurant.mainserver.security.SecurityConstants
import com.techtaurant.mainserver.security.handler.CustomAuthenticationEntryPoint
import com.techtaurant.mainserver.security.helper.CookieHelper
import com.techtaurant.mainserver.security.helper.JwtExceptionMapper
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserTokenRepository
import io.jsonwebtoken.ExpiredJwtException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * JWT 기반 인증 필터
 *
 * AccessToken에서 userId와 role을 추출하여 Stateless 인증을 수행합니다.
 * 일반 AccessToken은 JWT만으로 인증하고, 영구 토큰은 DB 등록 여부와 현재 사용자 권한을 추가로 확인합니다.
 */
@Component
class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
    private val userTokenRepository: UserTokenRepository,
    private val authenticationEntryPoint: CustomAuthenticationEntryPoint,
    private val cookieHelper: CookieHelper,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authentication = authenticateAccessToken(resolveAccessTokenCookies(request))

        if (authentication is AccessTokenAuthentication.Rejected) {
            request.setAttribute(SecurityConstants.ERROR_ATTRIBUTE, authentication.status)

            if (authentication.status in UNRECOVERABLE_TOKEN_STATUSES && !managesAuthCookiesItself(request)) {
                cookieHelper.deleteCookie(response, JwtConstants.ACCESS_TOKEN_COOKIE)
            }

            if (requiresEndedSessionResponse(request, authentication.status)) {
                authenticationEntryPoint.writeError(response, authentication.status)
                return
            }
        }

        filterChain.doFilter(request, response)
    }

    /**
     * 브라우저는 Domain·Path 조합이 다르면 이름이 같은 쿠키를 각각 보관해 한 요청에 여러 개를 함께 싣고,
     * RFC 6265는 서버가 그 순서에 기대지 말라고 정합니다.
     * 첫 쿠키만 읽으면 남아 있던 옛 토큰이 인증을 가로채므로, 쓸 수 있는 토큰을 찾을 때까지 후보를 훑습니다.
     *
     * 후보가 모두 실패하면 만료를 다른 실패보다 우선해 알립니다.
     * 만료만이 재발급으로 되살릴 수 있는 실패라서 클라이언트가 재발급을 시도할 근거가 됩니다.
     */
    private fun authenticateAccessToken(accessTokenCandidates: List<String>): AccessTokenAuthentication {
        if (accessTokenCandidates.isEmpty()) {
            return AccessTokenAuthentication.NoAccessToken
        }

        var hasExpiredToken = false
        var lastRejection: JwtStatus? = null

        for (token in accessTokenCandidates) {
            try {
                val claims = jwtTokenProvider.validateAndGetClaims(token)

                if (!canAuthenticateByTokenPolicy(claims, token)) {
                    SecurityContextHolder.clearContext()
                    lastRejection = JwtStatus.INVALID_TOKEN
                    continue
                }

                SecurityContextHolder.getContext().authentication = authenticationOf(claims)
                return AccessTokenAuthentication.Authenticated
            } catch (e: ExpiredJwtException) {
                hasExpiredToken = true
            } catch (e: Exception) {
                lastRejection = JwtExceptionMapper.mapToJwtStatus(e = e)
            }
        }

        return AccessTokenAuthentication.Rejected(
            if (hasExpiredToken) JwtStatus.ACCESS_TOKEN_EXPIRED else lastRejection ?: JwtStatus.INVALID_TOKEN,
        )
    }

    private fun authenticationOf(claims: JwtClaims): UsernamePasswordAuthenticationToken {
        // principal에는 userId만 담아 인증 이후 조회가 항상 최신 사용자 정보를 보게 합니다.
        return UsernamePasswordAuthenticationToken(
            claims.userId,
            null,
            listOf(SimpleGrantedAuthority(claims.role)),
        )
    }

    /**
     * 인증 없이도 응답하는 경로는 인가 계층이 요청을 통과시켜 세션이 끝났다는 사실이 클라이언트까지 전달되지 않습니다.
     * 그중 재발급으로 되살릴 수 있는 만료만 직접 401로 알려 클라이언트가 재발급으로 넘어가게 합니다.
     * 되살릴 수 없는 실패는 클라이언트가 지금 할 수 있는 일이 없으므로,
     * 쿠키만 걷어낸 뒤 비로그인 사용자로 통과시켜 공개 콘텐츠를 계속 보여줍니다.
     */
    private fun requiresEndedSessionResponse(
        request: HttpServletRequest,
        status: JwtStatus,
    ): Boolean {
        return status == JwtStatus.ACCESS_TOKEN_EXPIRED &&
            request.requestURI.startsWith("${SecurityConstants.OPEN_API_PREFIX}/") &&
            !managesAuthCookiesItself(request)
    }

    private fun managesAuthCookiesItself(request: HttpServletRequest): Boolean {
        return SELF_MANAGED_AUTH_COOKIE_PATH_PREFIXES.any { request.requestURI.startsWith(it) }
    }

    private fun resolveAccessTokenCookies(request: HttpServletRequest): List<String> {
        return request.cookies
            ?.filter { it.name == JwtConstants.ACCESS_TOKEN_COOKIE }
            ?.map { it.value }
            .orEmpty()
    }

    private fun canAuthenticateByTokenPolicy(
        claims: JwtClaims,
        token: String,
    ): Boolean {
        if (!claims.isPermanent) {
            return true
        }

        return isRegisteredPermanentTokenWithCurrentUserRole(claims, token)
    }

    private fun isRegisteredPermanentTokenWithCurrentUserRole(
        claims: JwtClaims,
        token: String,
    ): Boolean {
        val claimedRole = UserRole.fromKey(claims.role) ?: return false

        return userTokenRepository.existsByUserIdAndTokenHashAndUserRole(
            claims.userId,
            jwtTokenProvider.hashToken(token),
            claimedRole,
        )
    }

    /**
     * 인증 성공과 토큰 없음을 모두 null로 돌려주면 호출부가 둘을 구분할 수 없어 상태를 따로 둡니다.
     */
    private sealed interface AccessTokenAuthentication {
        data object Authenticated : AccessTokenAuthentication

        data object NoAccessToken : AccessTokenAuthentication

        data class Rejected(val status: JwtStatus) : AccessTokenAuthentication
    }

    private companion object {
        /**
         * 재발급으로 되살릴 수 없어 쿠키에서 걷어내야 하는 상태입니다.
         * 만료는 여기에 넣지 않습니다 — 재발급이 성공하면 새 쿠키가 덮어쓰는데,
         * 느린 요청의 응답이 재발급 뒤에 도착하면 방금 받은 쿠키를 지워 재발급이 끝없이 반복됩니다.
         */
        val UNRECOVERABLE_TOKEN_STATUSES =
            setOf(
                JwtStatus.INVALID_TOKEN,
                JwtStatus.MALFORMED_TOKEN,
                JwtStatus.UNSUPPORTED_TOKEN,
            )

        val SELF_MANAGED_AUTH_COOKIE_PATH_PREFIXES =
            listOf(
                "${SecurityConstants.OPEN_API_PREFIX}/auth/",
                "${SecurityConstants.OPEN_API_PREFIX}/dev/auth/",
            )
    }
}
