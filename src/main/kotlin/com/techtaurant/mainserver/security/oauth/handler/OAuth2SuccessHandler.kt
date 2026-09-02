package com.techtaurant.mainserver.security.oauth.handler

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.security.helper.CookieHelper
import com.techtaurant.mainserver.security.infrastructure.out.RefreshTokenStore
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.security.oauth.CustomOAuth2User
import com.techtaurant.mainserver.user.enums.UserStatus
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class OAuth2SuccessHandler(
    private val jwtTokenProvider: JwtTokenProvider,
    private val cookieHelper: CookieHelper,
    private val refreshTokenStore: RefreshTokenStore,
    private val redirectResolver: OAuth2RedirectResolver,
) : AuthenticationSuccessHandler {
    @Transactional
    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        val customOAuth2User = authentication.principal as CustomOAuth2User
        val user = customOAuth2User.getUser()
        val userId = user.id ?: throw ApiException(UserStatus.ID_NOT_FOUND)

        // AccessToken에 권한 포함하여 생성
        val accessToken = jwtTokenProvider.createAccessToken(userId, user.role)
        val refreshToken = jwtTokenProvider.createRefreshToken(userId)

        refreshTokenStore.save(userId, refreshToken)

        cookieHelper.addAuthCookie(
            request,
            response,
            JwtConstants.ACCESS_TOKEN_COOKIE,
            accessToken,
        )
        cookieHelper.addAuthCookie(
            request,
            response,
            JwtConstants.REFRESH_TOKEN_COOKIE,
            refreshToken,
        )

        val redirectUrl = redirectResolver.resolveSuccessRedirectUrl(request)
        response.sendRedirect(redirectUrl)
    }
}
