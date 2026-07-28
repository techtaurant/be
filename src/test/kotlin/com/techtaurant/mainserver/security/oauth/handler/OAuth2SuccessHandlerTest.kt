package com.techtaurant.mainserver.security.oauth.handler

import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.security.helper.CookieHelper
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtProperties
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.security.oauth.CustomOAuth2User
import com.techtaurant.mainserver.security.oauth.repository.HttpCookieOAuth2AuthorizationRequestRepository
import com.techtaurant.mainserver.security.service.RefreshTokenWhitelistService
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.security.core.Authentication
import java.util.UUID

@DisplayName("OAuth2SuccessHandler")
class OAuth2SuccessHandlerTest {
    private val jwtTokenProvider: JwtTokenProvider = mockk()
    private val jwtProperties =
        JwtProperties(
            secret = "test-secret",
            accessTokenExpireMs = 3_600_000,
            refreshTokenExpireMs = 604_800_000,
        )
    private val cookieHelper: CookieHelper = mockk(relaxed = true)
    private val refreshTokenWhitelistService: RefreshTokenWhitelistService = mockk(relaxed = true)
    private val authorizationRequestRepository: HttpCookieOAuth2AuthorizationRequestRepository = mockk(relaxed = true)
    private val redirectResolver: OAuth2RedirectResolver = mockk()
    private val handler =
        OAuth2SuccessHandler(
            jwtTokenProvider = jwtTokenProvider,
            jwtProperties = jwtProperties,
            cookieHelper = cookieHelper,
            refreshTokenWhitelistService = refreshTokenWhitelistService,
            cookieOAuth2AuthorizationRequestRepository = authorizationRequestRepository,
            redirectResolver = redirectResolver,
        )

    @Test
    @DisplayName("신규 로그인은 발급한 Refresh Token의 해시를 whitelist에 등록한다")
    fun newLogin_registersIssuedRefreshTokenHash() {
        val userId = UUID.randomUUID()
        val user =
            User(
                name = "oauth-user",
                email = "oauth@example.com",
                provider = OAuthProvider.GOOGLE,
                identifier = "oauth-id",
                role = UserRole.USER,
                profileImageUrl = "",
            ).apply { id = userId }
        val authentication =
            mockk<Authentication> {
                every { principal } returns CustomOAuth2User(user, emptyMap())
            }
        val request = mockk<HttpServletRequest>()
        val response = mockk<HttpServletResponse>(relaxed = true)

        every { jwtTokenProvider.createAccessToken(userId, UserRole.USER) } returns "access-token"
        every { jwtTokenProvider.createRefreshToken(userId) } returns "refresh-token"
        every { jwtTokenProvider.hashToken("refresh-token") } returns "refresh-token-hash"
        every { redirectResolver.resolveSuccessRedirectUrl(request) } returns "https://example.com/oauth/callback"

        handler.onAuthenticationSuccess(request, response, authentication)

        verify(exactly = 1) { refreshTokenWhitelistService.register(userId, "refresh-token-hash") }
        verify(exactly = 1) {
            cookieHelper.addCookie(
                response,
                JwtConstants.REFRESH_TOKEN_COOKIE,
                "refresh-token",
                (jwtProperties.refreshTokenExpireMs / 1000).toInt(),
            )
        }
        verify(exactly = 1) { authorizationRequestRepository.removeAuthorizationRequestCookies(response) }
        verify(exactly = 1) { response.sendRedirect("https://example.com/oauth/callback") }
    }
}
