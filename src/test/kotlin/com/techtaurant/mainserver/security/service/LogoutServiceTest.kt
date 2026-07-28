package com.techtaurant.mainserver.security.service

import com.techtaurant.mainserver.security.helper.CookieHelper
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.Jwts
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

class LogoutServiceTest {
    private val cookieHelper: CookieHelper = mockk(relaxed = true)
    private val jwtTokenProvider: JwtTokenProvider = mockk()
    private val refreshTokenWhitelistService: RefreshTokenWhitelistService = mockk(relaxed = true)
    private lateinit var logoutService: LogoutService

    @BeforeEach
    fun setUp() {
        logoutService =
            LogoutService(
                cookieHelper = cookieHelper,
                jwtTokenProvider = jwtTokenProvider,
                refreshTokenWhitelistService = refreshTokenWhitelistService,
            )
    }

    @Test
    @DisplayName("refreshToken 없이 accessToken만 전달되어도 로그아웃 시 서버 토큰과 쿠키를 폐기한다")
    fun logoutWithAccessTokenInvalidatesRefreshTokenAndDeletesCookies() {
        // given
        val userId = UUID.randomUUID()
        val request =
            mockk<HttpServletRequest> {
                every { cookies } returns arrayOf(Cookie(JwtConstants.ACCESS_TOKEN_COOKIE, "access-token"))
            }
        val response = mockk<HttpServletResponse>(relaxed = true)
        every { jwtTokenProvider.validateAndGetUserId("access-token") } returns userId

        // when
        logoutService.logout(request, response)

        // then
        verify(exactly = 1) { refreshTokenWhitelistService.revokeAll(userId) }
        verify(exactly = 1) { cookieHelper.deleteAllAuthCookies(response) }
    }

    @Test
    @DisplayName("만료된 accessToken만 전달되어도 로그아웃 시 서버 토큰과 쿠키를 폐기한다")
    fun logoutWithExpiredAccessTokenInvalidatesRefreshTokenAndDeletesCookies() {
        // given
        val userId = UUID.randomUUID()
        val request =
            mockk<HttpServletRequest> {
                every { cookies } returns arrayOf(Cookie(JwtConstants.ACCESS_TOKEN_COOKIE, "expired-access-token"))
            }
        val response = mockk<HttpServletResponse>(relaxed = true)
        val expiredClaims = Jwts.claims().subject(userId.toString()).build()
        every { jwtTokenProvider.validateAndGetUserId("expired-access-token") } throws
            ExpiredJwtException(null, expiredClaims, "expired")

        // when
        logoutService.logout(request, response)

        // then
        verify(exactly = 1) { refreshTokenWhitelistService.revokeAll(userId) }
        verify(exactly = 1) { cookieHelper.deleteAllAuthCookies(response) }
    }

    @Test
    @DisplayName("만료된 accessToken의 subject가 올바르지 않아도 로그아웃은 쿠키를 삭제한다")
    fun logoutWithMalformedExpiredAccessTokenDeletesCookies() {
        // given
        val request =
            mockk<HttpServletRequest> {
                every { cookies } returns arrayOf(Cookie(JwtConstants.ACCESS_TOKEN_COOKIE, "malformed-expired-token"))
            }
        val response = mockk<HttpServletResponse>(relaxed = true)
        val expiredClaims = Jwts.claims().subject("not-a-uuid").build()
        every { jwtTokenProvider.validateAndGetUserId("malformed-expired-token") } throws
            ExpiredJwtException(null, expiredClaims, "expired")

        // when
        logoutService.logout(request, response)

        // then
        verify(exactly = 0) { refreshTokenWhitelistService.revokeAll(any()) }
        verify(exactly = 1) { cookieHelper.deleteAllAuthCookies(response) }
    }

    @Test
    @DisplayName("accessToken 검증에 실패하면 refreshToken의 사용자로 서버 토큰을 폐기한다")
    fun logoutWithInvalidAccessTokenFallsBackToRefreshToken() {
        val userId = UUID.randomUUID()
        val request =
            mockk<HttpServletRequest> {
                every { cookies } returns
                    arrayOf(
                        Cookie(JwtConstants.ACCESS_TOKEN_COOKIE, "invalid-access-token"),
                        Cookie(JwtConstants.REFRESH_TOKEN_COOKIE, "refresh-token"),
                    )
            }
        val response = mockk<HttpServletResponse>(relaxed = true)
        every { jwtTokenProvider.validateAndGetUserId("invalid-access-token") } throws IllegalArgumentException()
        every { jwtTokenProvider.validateAndGetUserId("refresh-token") } returns userId

        logoutService.logout(request, response)

        verify(exactly = 1) { refreshTokenWhitelistService.revokeAll(userId) }
        verify(exactly = 1) { cookieHelper.deleteAllAuthCookies(response) }
    }
}
