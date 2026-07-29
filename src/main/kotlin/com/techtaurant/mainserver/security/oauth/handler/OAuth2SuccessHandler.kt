package com.techtaurant.mainserver.security.oauth.handler

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.security.cache.TokenCachePort
import com.techtaurant.mainserver.security.helper.CookieHelper
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtProperties
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.security.oauth.CustomOAuth2User
import com.techtaurant.mainserver.security.oauth.repository.HttpCookieOAuth2AuthorizationRequestRepository
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
    private val jwtProperties: JwtProperties,
    private val cookieHelper: CookieHelper,
    private val tokenCacheManager: TokenCachePort,
    private val cookieOAuth2AuthorizationRequestRepository: HttpCookieOAuth2AuthorizationRequestRepository,
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

        // userId 기반으로 refresh token 저장 (기존 토큰은 자동으로 덮어씌워짐)
        tokenCacheManager.saveRefreshToken(userId.toString(), refreshToken)

        cookieHelper.addCookie(
            response,
            JwtConstants.ACCESS_TOKEN_COOKIE,
            accessToken,
            (jwtProperties.accessTokenExpireMs / 1000).toInt(),
        )
        cookieHelper.addCookie(
            response,
            JwtConstants.REFRESH_TOKEN_COOKIE,
            refreshToken,
            (jwtProperties.refreshTokenExpireMs / 1000).toInt(),
        )

        // OAuth2 인증 완료 후 authorization request 쿠키 정리
        cookieOAuth2AuthorizationRequestRepository.removeAuthorizationRequestCookies(response)

        val redirectUrl = redirectResolver.resolveSuccessRedirectUrl(request)
        response.sendRedirect(redirectUrl)
    }
}
