package com.techtaurant.mainserver.security.oauth.handler

import com.techtaurant.mainserver.security.config.CorsProperties
import com.techtaurant.mainserver.security.helper.CookieHelper
import com.techtaurant.mainserver.security.oauth.repository.HttpCookieOAuth2AuthorizationRequestRepository
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI

/**
 * OAuth2 인증 완료 후 리다이렉트할 URL을 결정한다.
 * 프론트가 전달한 성공/실패 URI를 우선 사용하고, 허용 origin만 통과시켜 오픈 리다이렉트 공격을 방지한다.
 */
@Component
class OAuth2RedirectResolver(
    private val cookieHelper: CookieHelper,
    private val corsProperties: CorsProperties,
) {
    private val logger = LoggerFactory.getLogger(OAuth2RedirectResolver::class.java)
    private val allowedOriginPatterns = corsProperties.parsedAllowedOriginPatterns
    private val corsConfiguration = corsProperties.createCorsConfiguration()

    companion object {
        const val DEFAULT_SUCCESS_REDIRECT_URI = "/oauth/callback"
        const val DEFAULT_FAILURE_REDIRECT_URI = "/oauth/error"
        private const val DEFAULT_ORIGIN = "http://localhost:3000"
    }

    fun resolveSuccessRedirectUrl(request: HttpServletRequest): String =
        resolve(
            request = request,
            redirectUriCookieName = HttpCookieOAuth2AuthorizationRequestRepository.OAUTH2_SUCCESS_REDIRECT_URI_COOKIE,
            fallbackRedirectUri = DEFAULT_SUCCESS_REDIRECT_URI,
        )

    fun resolveFailureRedirectUrl(request: HttpServletRequest): String =
        resolve(
            request = request,
            redirectUriCookieName = HttpCookieOAuth2AuthorizationRequestRepository.OAUTH2_FAILURE_REDIRECT_URI_COOKIE,
            fallbackRedirectUri = DEFAULT_FAILURE_REDIRECT_URI,
        )

    private fun resolve(
        request: HttpServletRequest,
        redirectUriCookieName: String,
        fallbackRedirectUri: String,
    ): String {
        val origin = getOriginCookie(request)
        val redirectUri = cookieHelper.getCookie(request, redirectUriCookieName)
        logger.info(
            "resolve: originCookie={}, allowedOriginPatterns={}, redirectUriCookieName={}, redirectUriPresent={}",
            origin,
            allowedOriginPatterns,
            redirectUriCookieName,
            !redirectUri.isNullOrBlank(),
        )

        val validOrigin = resolveValidOrigin(origin)
        val redirectUrl =
            resolveRedirectUrl(
                redirectUri = redirectUri,
                validOrigin = validOrigin,
            ) ?: buildRedirectUrl(validOrigin, fallbackRedirectUri)

        logger.info("resolve: redirectUrl={}", redirectUrl)
        return redirectUrl
    }

    private fun getOriginCookie(request: HttpServletRequest): String? {
        return cookieHelper.getCookie(
            request,
            HttpCookieOAuth2AuthorizationRequestRepository.OAUTH2_ORIGIN_COOKIE,
        )?.trim()?.trimEnd('/')
    }

    private fun resolveValidOrigin(origin: String?): String {
        if (origin != null && corsConfiguration.checkOrigin(origin) != null) {
            return origin
        }

        logger.warn(
            "resolve: origin not in allowedOriginPatterns, falling back. origin={}, allowedOriginPatterns={}",
            origin,
            allowedOriginPatterns,
        )
        return resolveFallbackOrigin()
    }

    private fun resolveFallbackOrigin(): String =
        allowedOriginPatterns.firstNotNullOfOrNull { pattern ->
            runCatching {
                val normalizedPattern = pattern.trimEnd('/')
                URI(normalizedPattern).toOrigin()?.takeIf { it == normalizedPattern }
            }.getOrNull()
        } ?: DEFAULT_ORIGIN

    private fun resolveRedirectUrl(
        redirectUri: String?,
        validOrigin: String,
    ): String? {
        val targetUri = redirectUri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (isInternalPath(targetUri)) {
            return buildRedirectUrl(validOrigin, targetUri)
        }

        return resolveAllowedAbsoluteUrl(targetUri)
    }

    private fun isInternalPath(redirectUri: String): Boolean {
        return redirectUri.startsWith("/") && !redirectUri.startsWith("//")
    }

    private fun buildRedirectUrl(
        origin: String,
        path: String,
    ): String {
        return "${origin.trimEnd('/')}$path"
    }

    private fun resolveAllowedAbsoluteUrl(redirectUri: String): String? {
        return try {
            val uri = URI(redirectUri)
            val origin = uri.toOrigin()
            if (origin != null && corsConfiguration.checkOrigin(origin) != null) {
                redirectUri
            } else {
                logger.warn(
                    "resolve: redirectUri origin not allowed. redirectOrigin={}, allowedOriginPatterns={}",
                    origin,
                    allowedOriginPatterns,
                )
                null
            }
        } catch (e: Exception) {
            logger.warn("resolve: invalid redirectUri={}", redirectUri)
            null
        }
    }

    private fun URI.toOrigin(): String? {
        val scheme = this.scheme ?: return null
        val host = this.host ?: return null
        val port = if (this.port != -1) ":${this.port}" else ""
        return "$scheme://$host$port".trimEnd('/')
    }
}
