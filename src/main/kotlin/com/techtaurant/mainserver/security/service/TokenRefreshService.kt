package com.techtaurant.mainserver.security.service

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.security.helper.CookieHelper
import com.techtaurant.mainserver.security.helper.JwtExceptionMapper
import com.techtaurant.mainserver.security.infrastructure.out.RefreshTokenStore
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtStatus
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.jsonwebtoken.ExpiredJwtException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class TokenRefreshService(
    private val cookieHelper: CookieHelper,
    private val jwtTokenProvider: JwtTokenProvider,
    private val refreshTokenStore: RefreshTokenStore,
    private val userRepository: UserRepository,
) {
    @Transactional
    fun execute(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val refreshTokenCandidates = cookieHelper.getCookies(request, JwtConstants.REFRESH_TOKEN_COOKIE)
        if (refreshTokenCandidates.isEmpty()) {
            throw ApiException(JwtStatus.MISSING_REFRESH_TOKEN)
        }

        val userId = consumeReissuableSessionUserId(refreshTokenCandidates)

        // 권한 변경이 재발급에 반영되도록 최신 사용자를 다시 읽는다
        val user =
            userRepository.findById(userId).orElseThrow {
                ApiException(JwtStatus.INVALID_REFRESH_TOKEN)
            }

        val newAccessToken = jwtTokenProvider.createAccessToken(userId, user.role)
        val newRefreshToken = jwtTokenProvider.createRefreshToken(userId)

        // 쓴 토큰은 후보를 고르며 이미 폐기했으므로, 새 토큰만 더해 다른 기기의 세션을 남긴다
        refreshTokenStore.save(userId, newRefreshToken)

        cookieHelper.addAuthCookie(
            request,
            response,
            JwtConstants.ACCESS_TOKEN_COOKIE,
            newAccessToken,
        )
        cookieHelper.addAuthCookie(
            request,
            response,
            JwtConstants.REFRESH_TOKEN_COOKIE,
            newRefreshToken,
        )
    }

    /**
     * 브라우저는 Path가 다르면 이름이 같은 refreshToken 쿠키를 각각 보관해 한 요청에 함께 싣습니다.
     * 첫 쿠키만 읽으면 남아 있던 옛 토큰이 재발급을 가로채고, 재발급이 실패하면 새 쿠키를 내려보내
     * 옛 쿠키를 걷어낼 기회도 오지 않으므로 자력으로 회복할 수 없는 상태에 갇힙니다.
     * 그래서 저장소가 인정하는 토큰을 찾을 때까지 후보를 훑습니다.
     *
     * 인정 여부를 먼저 묻고 나중에 따로 폐기하면 그사이에 도착한 같은 토큰의 요청도 함께 통과해
     * 한 세션에서 유효한 refreshToken이 둘로 늘어납니다. 그래서 폐기 자체를 회전의 관문으로 삼아,
     * 그 행을 실제로 지운 요청만 재발급을 이어가게 합니다.
     *
     * 후보가 모두 실패하면 만료를 다른 실패보다 우선해 알립니다.
     * 만료만이 재로그인이 필요한 시점을 클라이언트에게 정확히 알려주는 실패이기 때문입니다.
     */
    private fun consumeReissuableSessionUserId(refreshTokenCandidates: List<String>): UUID {
        var hasExpiredToken = false
        var lastRejection: JwtStatus? = null

        for (refreshToken in refreshTokenCandidates) {
            val userId =
                try {
                    jwtTokenProvider.validateAndGetRefreshTokenUserId(refreshToken)
                } catch (e: ExpiredJwtException) {
                    hasExpiredToken = true
                    continue
                } catch (e: Exception) {
                    lastRejection = JwtExceptionMapper.mapToJwtStatus(e = e)
                    continue
                }

            if (refreshTokenStore.consume(userId, refreshToken)) {
                return userId
            }

            lastRejection = JwtStatus.INVALID_REFRESH_TOKEN
        }

        throw ApiException(
            if (hasExpiredToken) {
                JwtStatus.REFRESH_TOKEN_EXPIRED
            } else {
                lastRejection ?: JwtStatus.INVALID_REFRESH_TOKEN
            },
        )
    }
}
