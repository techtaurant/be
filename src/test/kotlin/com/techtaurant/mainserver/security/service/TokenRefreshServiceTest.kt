package com.techtaurant.mainserver.security.service

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.security.helper.CookieHelper
import com.techtaurant.mainserver.security.infrastructure.out.RefreshTokenStore
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtStatus
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.jsonwebtoken.ExpiredJwtException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Optional
import java.util.UUID

class TokenRefreshServiceTest {
    private lateinit var tokenRefreshService: TokenRefreshService
    private val cookieHelper: CookieHelper = mockk()
    private val jwtTokenProvider: JwtTokenProvider = mockk()
    private val refreshTokenStore: RefreshTokenStore = mockk()
    private val userRepository: UserRepository = mockk()

    @BeforeEach
    fun setUp() {
        tokenRefreshService =
            TokenRefreshService(
                cookieHelper,
                jwtTokenProvider,
                refreshTokenStore,
                userRepository,
            )
    }

    @Test
    @DisplayName("토큰 리프레시 성공")
    fun `token refresh success`() {
        // given
        val userId = UUID.randomUUID()
        val refreshTokenValue = "valid-refresh-token"
        val newAccessToken = "new-access-token"
        val newRefreshToken = "new-refresh-token"
        val request = mockk<HttpServletRequest>()
        val response = mockk<HttpServletResponse>(relaxed = true)
        val user =
            mockk<User> {
                every { role } returns UserRole.USER
            }

        every { cookieHelper.getCookie(request, JwtConstants.REFRESH_TOKEN_COOKIE) } returns refreshTokenValue
        every { cookieHelper.addAuthCookie(any(), any(), any(), any()) } returns Unit
        every { jwtTokenProvider.validateAndGetRefreshTokenUserId(refreshTokenValue) } returns userId
        every { refreshTokenStore.exists(userId, refreshTokenValue) } returns true
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { jwtTokenProvider.createAccessToken(userId, UserRole.USER) } returns newAccessToken
        every { jwtTokenProvider.createRefreshToken(userId) } returns newRefreshToken
        every { refreshTokenStore.delete(userId, refreshTokenValue) } returns Unit
        every { refreshTokenStore.save(userId, newRefreshToken) } returns Unit

        // when
        tokenRefreshService.execute(request, response)

        // then
        verify {
            cookieHelper.addAuthCookie(
                request,
                response,
                JwtConstants.ACCESS_TOKEN_COOKIE,
                newAccessToken,
            )
        }
        verify {
            cookieHelper.addAuthCookie(
                request,
                response,
                JwtConstants.REFRESH_TOKEN_COOKIE,
                newRefreshToken,
            )
        }
        verify { refreshTokenStore.delete(userId, refreshTokenValue) }
        verify { refreshTokenStore.save(userId, newRefreshToken) }
    }

    @Test
    @DisplayName("저장소에 없거나 일치하지 않는 리프레시 토큰으로 요청 시 예외 발생 (토큰 재사용 공격 방어)")
    fun `refresh with token missing from store`() {
        // given
        val userId = UUID.randomUUID()
        val refreshTokenValue = "non-existent-token"
        val request = mockk<HttpServletRequest>()
        val response = mockk<HttpServletResponse>()

        every { cookieHelper.getCookie(request, JwtConstants.REFRESH_TOKEN_COOKIE) } returns refreshTokenValue
        every { jwtTokenProvider.validateAndGetRefreshTokenUserId(refreshTokenValue) } returns userId
        every { refreshTokenStore.exists(userId, refreshTokenValue) } returns false

        // when & then
        val exception =
            assertThrows<ApiException> {
                tokenRefreshService.execute(request, response)
            }
        assertEquals(JwtStatus.INVALID_REFRESH_TOKEN, exception.status)
    }

    @Test
    @DisplayName("만료된 리프레시 토큰으로 요청 시 예외 발생")
    fun `refresh with expired token`() {
        // given
        val refreshTokenValue = "expired-refresh-token"
        val request = mockk<HttpServletRequest>()
        val response = mockk<HttpServletResponse>()

        every { cookieHelper.getCookie(request, JwtConstants.REFRESH_TOKEN_COOKIE) } returns refreshTokenValue
        every { jwtTokenProvider.validateAndGetRefreshTokenUserId(refreshTokenValue) } throws ExpiredJwtException(null, null, "expired")

        // when & then
        val exception =
            assertThrows<ApiException> {
                tokenRefreshService.execute(request, response)
            }
        assertEquals(JwtStatus.REFRESH_TOKEN_EXPIRED, exception.status)
    }

    @Test
    @DisplayName("User가 존재하지 않을 경우 예외 발생")
    fun `refresh with non-existent user`() {
        // given
        val userId = UUID.randomUUID()
        val refreshTokenValue = "valid-refresh-token"
        val request = mockk<HttpServletRequest>()
        val response = mockk<HttpServletResponse>()

        every { cookieHelper.getCookie(request, JwtConstants.REFRESH_TOKEN_COOKIE) } returns refreshTokenValue
        every { jwtTokenProvider.validateAndGetRefreshTokenUserId(refreshTokenValue) } returns userId
        every { refreshTokenStore.exists(userId, refreshTokenValue) } returns true
        every { userRepository.findById(userId) } returns Optional.empty()

        // when & then
        val exception =
            assertThrows<ApiException> {
                tokenRefreshService.execute(request, response)
            }
        assertEquals(JwtStatus.INVALID_REFRESH_TOKEN, exception.status)
    }
}
