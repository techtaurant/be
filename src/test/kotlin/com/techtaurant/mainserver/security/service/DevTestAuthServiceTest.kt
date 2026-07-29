package com.techtaurant.mainserver.security.service

import com.techtaurant.mainserver.security.cache.TokenCachePort
import com.techtaurant.mainserver.security.dto.DevTestLoginRequest
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.security.helper.CookieHelper
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtProperties
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.application.UserUniqueNameService
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

class DevTestAuthServiceTest {
    private val userRepository: UserRepository = mockk()
    private val jwtTokenProvider: JwtTokenProvider = mockk()
    private val jwtProperties: JwtProperties =
        JwtProperties(
            secret = "test-secret",
            accessTokenExpireMs = 3600000,
            refreshTokenExpireMs = 604800000,
        )
    private val cookieHelper: CookieHelper = mockk(relaxed = true)
    private val tokenCacheManager: TokenCachePort = mockk(relaxed = true)
    private val userUniqueNameService: UserUniqueNameService = mockk()
    private lateinit var devTestAuthService: DevTestAuthService

    @BeforeEach
    fun setUp() {
        devTestAuthService =
            DevTestAuthService(
                userRepository,
                jwtTokenProvider,
                jwtProperties,
                cookieHelper,
                tokenCacheManager,
                userUniqueNameService,
            )
    }

    @Test
    @DisplayName("dev 테스트 사용자가 없으면 유니크 닉네임 정책으로 새 사용자를 생성한다")
    fun `create dev test user with unique name policy`() {
        val identifier = "dev-user"
        val request = DevTestLoginRequest(identifier = identifier, password = "dev-password", role = UserRole.ADMIN)
        val response = mockk<HttpServletResponse>(relaxed = true)
        val userId = UUID.randomUUID()
        val createdUser =
            User(
                name = "generatedname",
                email = "$identifier@dev.local",
                provider = OAuthProvider.DEV_LOCAL,
                identifier = identifier,
                role = UserRole.ADMIN,
                profileImageUrl = "",
            ).apply {
                id = userId
            }

        every { userRepository.findByIdentifierAndProvider(identifier, OAuthProvider.DEV_LOCAL) } returns null
        every { userUniqueNameService.saveNewUser(any()) } returns createdUser
        every { jwtTokenProvider.createAccessToken(userId, UserRole.ADMIN) } returns "access-token"
        every { jwtTokenProvider.createRefreshToken(userId) } returns "refresh-token"

        val result = devTestAuthService.execute(request, response)

        assertEquals("access-token", result.accessToken)
        assertEquals("refresh-token", result.refreshToken)
        verify(exactly = 1) {
            userUniqueNameService.saveNewUser(
                withArg {
                    assertEquals(identifier, it.name)
                    assertEquals("$identifier@dev.local", it.email)
                    assertEquals(UserRole.ADMIN, it.role)
                },
            )
        }
        verify {
            cookieHelper.addCookie(
                response,
                JwtConstants.ACCESS_TOKEN_COOKIE,
                "access-token",
                (jwtProperties.accessTokenExpireMs / 1000).toInt(),
            )
        }
        verify {
            cookieHelper.addCookie(
                response,
                JwtConstants.REFRESH_TOKEN_COOKIE,
                "refresh-token",
                (jwtProperties.refreshTokenExpireMs / 1000).toInt(),
            )
        }
        verify { tokenCacheManager.saveRefreshToken(userId.toString(), "refresh-token") }
    }

    @Test
    @DisplayName("기존 dev 테스트 사용자는 요청한 권한으로 갱신한다")
    fun `update existing dev test user role`() {
        val identifier = "existing-user"
        val request = DevTestLoginRequest(identifier = identifier, password = "dev-password", role = UserRole.ADMIN)
        val response = mockk<HttpServletResponse>(relaxed = true)
        val userId = UUID.randomUUID()
        val existingUser =
            User(
                name = identifier,
                email = "$identifier@dev.local",
                provider = OAuthProvider.DEV_LOCAL,
                identifier = identifier,
                role = UserRole.USER,
                profileImageUrl = "",
            ).apply {
                id = userId
            }

        every { userRepository.findByIdentifierAndProvider(identifier, OAuthProvider.DEV_LOCAL) } returns existingUser
        every { jwtTokenProvider.createAccessToken(userId, UserRole.ADMIN) } returns "access-token"
        every { jwtTokenProvider.createRefreshToken(userId) } returns "refresh-token"

        val result = devTestAuthService.execute(request, response)

        assertEquals(UserRole.ADMIN, existingUser.role)
        assertEquals("access-token", result.accessToken)
        assertEquals("refresh-token", result.refreshToken)
        verify(exactly = 0) { userUniqueNameService.saveNewUser(any()) }
    }
}
