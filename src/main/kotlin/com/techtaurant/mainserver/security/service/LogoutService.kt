package com.techtaurant.mainserver.security.service

import com.techtaurant.mainserver.security.cache.TokenCachePort
import com.techtaurant.mainserver.security.helper.CookieHelper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 로그아웃 서비스
 *
 * 캐시에서 토큰을 무효화하고 인증 쿠키를 삭제합니다.
 * 멱등성을 보장하여 중복 호출 시에도 안전합니다.
 */
@Service
class LogoutService(
    private val cookieHelper: CookieHelper,
    private val tokenCacheManager: TokenCachePort,
) {
    fun logout(
        authenticatedUserId: UUID,
        response: HttpServletResponse,
    ) {
        tokenCacheManager.deleteRefreshToken(authenticatedUserId.toString())
        cookieHelper.deleteAllAuthCookies(response)
    }
}
