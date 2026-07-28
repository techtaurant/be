package com.techtaurant.mainserver.security.service

import com.techtaurant.mainserver.security.helper.CookieHelper
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import io.jsonwebtoken.ExpiredJwtException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 로그아웃 서비스
 *
 * 사용자의 Refresh Token을 모두 폐기하고 인증 쿠키를 삭제합니다.
 * 멱등성을 보장하여 중복 호출 시에도 안전합니다.
 */
@Service
class LogoutService(
    private val cookieHelper: CookieHelper,
    private val jwtTokenProvider: JwtTokenProvider,
    private val refreshTokenWhitelistService: RefreshTokenWhitelistService,
) {
    fun logout(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        // 쿠키에서 토큰 추출
        val accessToken = request.cookies?.find { it.name == JwtConstants.ACCESS_TOKEN_COOKIE }?.value
        val refreshToken = request.cookies?.find { it.name == JwtConstants.REFRESH_TOKEN_COOKIE }?.value

        // 서버 whitelist에서 사용자의 모든 Refresh Token 무효화
        invalidateTokens(accessToken, refreshToken)

        // 쿠키 삭제
        cookieHelper.deleteAllAuthCookies(response)
    }

    /**
     * 서버 whitelist에서 사용자의 모든 Refresh Token을 무효화합니다.
     *
     * ACCESS_TOKEN은 서버에 저장하지 않으므로 별도 삭제가 불필요합니다.
     *
     * @param accessToken AccessToken 값 (nullable, userId 추출용)
     * @param refreshToken RefreshToken 값 (nullable, userId 추출용)
     */
    private fun invalidateTokens(
        accessToken: String?,
        refreshToken: String?,
    ) {
        // userId 추출 (둘 중 하나라도 있으면 가능)
        val userId = extractUserId(accessToken, refreshToken) ?: return

        refreshTokenWhitelistService.revokeAll(userId)
    }

    /**
     * 토큰에서 userId를 추출합니다.
     *
     * 검증에 실패해도 예외를 던지지 않고 null을 반환합니다.
     * (이미 만료된 토큰으로 로그아웃하는 경우 허용)
     *
     * @return userId 또는 null
     */
    private fun extractUserId(
        accessToken: String?,
        refreshToken: String?,
    ): UUID? =
        sequenceOf(accessToken, refreshToken)
            .filterNotNull()
            .mapNotNull(::extractUserId)
            .firstOrNull()

    private fun extractUserId(token: String): UUID? {
        return try {
            jwtTokenProvider.validateAndGetUserId(token)
        } catch (e: ExpiredJwtException) {
            runCatching { UUID.fromString(e.claims.subject) }.getOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
