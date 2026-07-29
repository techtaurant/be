package com.techtaurant.mainserver.security.service

import com.techtaurant.mainserver.security.cache.TokenCachePort
import com.techtaurant.mainserver.security.helper.CookieHelper
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

class LogoutServiceTest {
    private val cookieHelper: CookieHelper = mockk(relaxed = true)
    private val tokenCacheManager: TokenCachePort = mockk(relaxed = true)
    private lateinit var logoutService: LogoutService

    @BeforeEach
    fun setUp() {
        logoutService =
            LogoutService(
                cookieHelper = cookieHelper,
                tokenCacheManager = tokenCacheManager,
            )
    }

    @Test
    @DisplayName("인증된 사용자의 서버 refreshToken과 인증 쿠키를 폐기한다")
    fun logoutInvalidatesAuthenticatedUsersRefreshTokenAndDeletesCookies() {
        val authenticatedUserId = UUID.randomUUID()
        val response = mockk<HttpServletResponse>(relaxed = true)

        logoutService.logout(authenticatedUserId, response)

        verify(exactly = 1) {
            tokenCacheManager.deleteRefreshToken(authenticatedUserId.toString())
        }
        verify(exactly = 1) { cookieHelper.deleteAllAuthCookies(response) }
    }
}
