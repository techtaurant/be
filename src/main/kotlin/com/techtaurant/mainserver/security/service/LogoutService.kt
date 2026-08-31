package com.techtaurant.mainserver.security.service

import com.techtaurant.mainserver.security.helper.CookieHelper
import com.techtaurant.mainserver.security.infrastructure.out.RefreshTokenStore
import com.techtaurant.mainserver.security.jwt.JwtConstants
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 로그아웃 서비스
 *
 * 요청을 보낸 기기의 refresh token만 폐기하고 인증 쿠키를 삭제합니다.
 * 다른 기기의 세션은 그대로 두며, 폐기할 토큰이 없어도 쿠키 정리는 그대로 수행해 멱등성을 지킵니다.
 */
@Service
class LogoutService(
    private val cookieHelper: CookieHelper,
    private val refreshTokenStore: RefreshTokenStore,
) {
    fun logout(
        authenticatedUserId: UUID,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        cookieHelper.getCookie(request, JwtConstants.REFRESH_TOKEN_COOKIE)
            ?.let { refreshTokenStore.delete(authenticatedUserId, it) }
        cookieHelper.deleteAllAuthCookies(response)
    }
}
