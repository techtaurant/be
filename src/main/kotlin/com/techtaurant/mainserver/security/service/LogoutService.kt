package com.techtaurant.mainserver.security.service

import com.techtaurant.mainserver.security.helper.CookieHelper
import com.techtaurant.mainserver.security.infrastructure.out.RefreshTokenStore
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Service

/**
 * 로그아웃 서비스
 *
 * 폐기할 토큰이 없어도 쿠키 정리는 그대로 수행해 여러 번 호출해도 결과가 같게 유지합니다.
 */
@Service
class LogoutService(
    private val cookieHelper: CookieHelper,
    private val jwtTokenProvider: JwtTokenProvider,
    private val refreshTokenStore: RefreshTokenStore,
) {
    /**
     * 요청에 실려 온 refreshToken에 해당하는 세션만 폐기해 다른 기기의 로그인을 남깁니다.
     * 이름이 같은 쿠키가 여러 개 올 수 있으므로 후보를 모두 훑습니다.
     */
    fun logoutCurrentDevice(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        cookieHelper.getCookies(request, JwtConstants.REFRESH_TOKEN_COOKIE)
            .forEach { revokeIfServerIssued(it) }
        cookieHelper.deleteAllAuthCookies(response)
    }

    /**
     * refreshToken 쿠키가 실리지 않는 옛 경로를 위해 인증 쿠키만 걷어냅니다.
     * 폐기할 토큰을 요청에서 얻을 수 없어 서버에 남은 세션은 만료될 때까지 유지되므로,
     * 그 기기의 세션까지 끊어야 하는 클라이언트는 logoutCurrentDevice 경로로 옮겨야 합니다.
     */
    fun clearAuthCookies(response: HttpServletResponse) {
        cookieHelper.deleteAllAuthCookies(response)
    }

    /**
     * 이 서버가 발급하지 않은 쿠키 값은 폐기할 세션을 특정할 수 없으므로 건너뜁니다.
     */
    private fun revokeIfServerIssued(refreshToken: String) {
        val userId =
            runCatching { jwtTokenProvider.validateAndGetRefreshTokenUserId(refreshToken) }
                .getOrNull() ?: return

        refreshTokenStore.delete(userId, refreshToken)
    }
}
