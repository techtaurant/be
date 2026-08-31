package com.techtaurant.mainserver.security.service

import com.techtaurant.mainserver.security.helper.CookieHelper
import com.techtaurant.mainserver.security.infrastructure.out.RefreshTokenStore
import com.techtaurant.mainserver.security.jwt.JwtConstants
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

class LogoutServiceTest {
    private val cookieHelper: CookieHelper = mockk(relaxed = true)
    private val refreshTokenStore: RefreshTokenStore = mockk(relaxed = true)
    private lateinit var logoutService: LogoutService

    @BeforeEach
    fun setUp() {
        logoutService =
            LogoutService(
                cookieHelper = cookieHelper,
                refreshTokenStore = refreshTokenStore,
            )
    }

    @Test
    @DisplayName("요청을 보낸 기기의 refreshToken만 폐기하고 인증 쿠키를 삭제한다")
    fun logoutInvalidatesOnlyTheRequestingDevicesRefreshToken() {
        val authenticatedUserId = UUID.randomUUID()
        val request = mockk<HttpServletRequest>()
        val response = mockk<HttpServletResponse>(relaxed = true)
        every { cookieHelper.getCookie(request, JwtConstants.REFRESH_TOKEN_COOKIE) } returns REFRESH_TOKEN

        logoutService.logout(authenticatedUserId, request, response)

        verify(exactly = 1) { refreshTokenStore.delete(authenticatedUserId, REFRESH_TOKEN) }
        verify(exactly = 1) { cookieHelper.deleteAllAuthCookies(response) }
    }

    @Test
    @DisplayName("refreshToken 쿠키가 없으면 폐기할 세션이 없으므로 쿠키 정리만 수행한다")
    fun logoutWithoutRefreshTokenCookieOnlyClearsCookies() {
        val authenticatedUserId = UUID.randomUUID()
        val request = mockk<HttpServletRequest>()
        val response = mockk<HttpServletResponse>(relaxed = true)
        every { cookieHelper.getCookie(request, JwtConstants.REFRESH_TOKEN_COOKIE) } returns null

        logoutService.logout(authenticatedUserId, request, response)

        verify(exactly = 0) { refreshTokenStore.delete(any(), any()) }
        verify(exactly = 1) { cookieHelper.deleteAllAuthCookies(response) }
    }

    private companion object {
        const val REFRESH_TOKEN = "device-refresh-token"
    }
}
