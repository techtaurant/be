package com.techtaurant.mainserver.security.service

import com.techtaurant.mainserver.security.helper.CookieHelper
import com.techtaurant.mainserver.security.infrastructure.out.RefreshTokenStore
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import io.jsonwebtoken.MalformedJwtException
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
    private val jwtTokenProvider: JwtTokenProvider = mockk()
    private val refreshTokenStore: RefreshTokenStore = mockk(relaxed = true)
    private lateinit var logoutService: LogoutService

    @BeforeEach
    fun setUp() {
        logoutService =
            LogoutService(
                cookieHelper = cookieHelper,
                jwtTokenProvider = jwtTokenProvider,
                refreshTokenStore = refreshTokenStore,
            )
    }

    @Test
    @DisplayName("요청에 실려 온 refreshToken의 세션만 폐기하고 인증 쿠키를 삭제한다")
    fun logoutCurrentDeviceRevokesOnlyTheRequestingSession() {
        // given
        val userId = UUID.randomUUID()
        val request = mockk<HttpServletRequest>()
        val response = mockk<HttpServletResponse>(relaxed = true)
        every { cookieHelper.getCookies(request, JwtConstants.REFRESH_TOKEN_COOKIE) } returns listOf(REFRESH_TOKEN)
        every { jwtTokenProvider.validateAndGetRefreshTokenUserId(REFRESH_TOKEN) } returns userId

        // when
        logoutService.logoutCurrentDevice(request, response)

        // then
        verify(exactly = 1) { refreshTokenStore.delete(userId, REFRESH_TOKEN) }
        verify(exactly = 0) { refreshTokenStore.deleteAllByUserId(any()) }
        verify(exactly = 1) { cookieHelper.deleteAllAuthCookies(response) }
    }

    @Test
    @DisplayName("이름이 같은 refreshToken 쿠키가 여러 개 오면 후보를 모두 폐기한다")
    fun logoutCurrentDeviceRevokesEveryDuplicateCookie() {
        // given
        val userId = UUID.randomUUID()
        val request = mockk<HttpServletRequest>()
        val response = mockk<HttpServletResponse>(relaxed = true)
        every { cookieHelper.getCookies(request, JwtConstants.REFRESH_TOKEN_COOKIE) } returns
            listOf(REFRESH_TOKEN, STALE_REFRESH_TOKEN)
        every { jwtTokenProvider.validateAndGetRefreshTokenUserId(REFRESH_TOKEN) } returns userId
        every { jwtTokenProvider.validateAndGetRefreshTokenUserId(STALE_REFRESH_TOKEN) } returns userId

        // when
        logoutService.logoutCurrentDevice(request, response)

        // then
        verify(exactly = 1) { refreshTokenStore.delete(userId, REFRESH_TOKEN) }
        verify(exactly = 1) { refreshTokenStore.delete(userId, STALE_REFRESH_TOKEN) }
    }

    @Test
    @DisplayName("서버가 발급하지 않은 쿠키 값은 건너뛰고 쿠키 정리는 그대로 수행한다")
    fun logoutCurrentDeviceSkipsUnparseableCookieValue() {
        // given
        val request = mockk<HttpServletRequest>()
        val response = mockk<HttpServletResponse>(relaxed = true)
        every { cookieHelper.getCookies(request, JwtConstants.REFRESH_TOKEN_COOKIE) } returns listOf("not-a-jwt")
        every { jwtTokenProvider.validateAndGetRefreshTokenUserId("not-a-jwt") } throws MalformedJwtException("malformed")

        // when
        logoutService.logoutCurrentDevice(request, response)

        // then
        verify(exactly = 0) { refreshTokenStore.delete(any(), any()) }
        verify(exactly = 1) { cookieHelper.deleteAllAuthCookies(response) }
    }

    @Test
    @DisplayName("refreshToken 쿠키가 없으면 폐기할 세션이 없으므로 쿠키 정리만 수행한다")
    fun logoutCurrentDeviceWithoutRefreshTokenCookieOnlyClearsCookies() {
        // given
        val request = mockk<HttpServletRequest>()
        val response = mockk<HttpServletResponse>(relaxed = true)
        every { cookieHelper.getCookies(request, JwtConstants.REFRESH_TOKEN_COOKIE) } returns emptyList()

        // when
        logoutService.logoutCurrentDevice(request, response)

        // then
        verify(exactly = 0) { refreshTokenStore.delete(any(), any()) }
        verify(exactly = 1) { cookieHelper.deleteAllAuthCookies(response) }
    }

    @Test
    @DisplayName("옛 경로는 refreshToken 쿠키를 받지 못하므로 인증된 사용자의 세션을 모두 폐기한다")
    fun logoutAllDevicesRevokesEverySessionOfTheUser() {
        // given
        val authenticatedUserId = UUID.randomUUID()
        val response = mockk<HttpServletResponse>(relaxed = true)

        // when
        logoutService.logoutAllDevices(authenticatedUserId, response)

        // then
        verify(exactly = 1) { refreshTokenStore.deleteAllByUserId(authenticatedUserId) }
        verify(exactly = 1) { cookieHelper.deleteAllAuthCookies(response) }
    }

    private companion object {
        const val REFRESH_TOKEN = "device-refresh-token"
        const val STALE_REFRESH_TOKEN = "stale-device-refresh-token"
    }
}
