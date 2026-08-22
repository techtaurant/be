package com.techtaurant.mainserver.security.jwt

import com.techtaurant.mainserver.security.SecurityConstants
import com.techtaurant.mainserver.security.handler.CustomAuthenticationEntryPoint
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
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = resolveToken(request)

        if (token != null) {
            try {
                // JWT에서 userId와 role을 추출합니다.
                val claims = jwtTokenProvider.validateAndGetClaims(token)

                if (!canAuthenticateByTokenPolicy(claims, token)) {
                    SecurityContextHolder.clearContext()
                    request.setAttribute(SecurityConstants.ERROR_ATTRIBUTE, JwtStatus.INVALID_TOKEN)
                    filterChain.doFilter(request, response)
                    return
                }

                // 권한 생성
                val authorities = listOf(SimpleGrantedAuthority(claims.role))

                // SecurityContext에 인증 정보 설정 (principal: userId)
                val authentication =
                    UsernamePasswordAuthenticationToken(
                        claims.userId, // principal: userId만 저장
                        null,
                        authorities,
                    )
                SecurityContextHolder.getContext().authentication = authentication
            } catch (e: ExpiredJwtException) {
                request.setAttribute(SecurityConstants.ERROR_ATTRIBUTE, JwtStatus.ACCESS_TOKEN_EXPIRED)

                if (requiresExpiredTokenResponse(request)) {
                    authenticationEntryPoint.writeError(response, JwtStatus.ACCESS_TOKEN_EXPIRED)
                    return
                }
            } catch (e: Exception) {
                request.setAttribute(SecurityConstants.ERROR_ATTRIBUTE, JwtExceptionMapper.mapToJwtStatus(e = e))
            }
        }

        filterChain.doFilter(request, response)
    }

    /**
     * 인증 없이도 응답하는 경로는 인가 계층이 요청을 통과시켜 토큰이 만료됐다는 사실이 클라이언트까지 전달되지 않습니다.
     * 재발급으로 되살릴 수 있는 만료만 이 경로에서 직접 401로 알려 클라이언트가 재발급을 시도하게 합니다.
     * 토큰을 새로 발급하는 경로는 만료된 accessToken을 들고 오는 것이 정상이므로 제외합니다.
     */
    private fun requiresExpiredTokenResponse(request: HttpServletRequest): Boolean {
        val path = request.requestURI

        return path.startsWith("${SecurityConstants.OPEN_API_PREFIX}/") &&
            TOKEN_ISSUING_PATH_PREFIXES.none { path.startsWith(it) }
    }

    private fun resolveToken(request: HttpServletRequest): String? {
        // 1. Authorization 헤더에서 토큰 확인
        val bearerToken = request.getHeader("Authorization")
        if (bearerToken != null && bearerToken.startsWith(JwtConstants.BEARER_PREFIX)) {
            return bearerToken.substring(JwtConstants.BEARER_PREFIX.length)
        }

        // 2. 쿠키에서 토큰 확인
        return request.cookies?.find { it.name == JwtConstants.ACCESS_TOKEN_COOKIE }?.value
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

    private companion object {
        val TOKEN_ISSUING_PATH_PREFIXES =
            listOf(
                "${SecurityConstants.OPEN_API_PREFIX}/auth/",
                "${SecurityConstants.OPEN_API_PREFIX}/dev/auth/",
            )
    }
}
