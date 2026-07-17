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

    companion object {
        const val DEFAULT_SUCCESS_REDIRECT_URI = "/oauth/callback"
        const val DEFAULT_FAILURE_REDIRECT_URI = "/oauth/error"
        private const val DEFAULT_ORIGIN = "http://localhost:3000"
        private const val WILDCARD_HOST_PLACEHOLDER = "wildcard"
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
        val allowedOrigins =
            corsProperties.allowedOrigins
                .split(",")
                .map { it.trim().trimEnd('/') }
                .filter { it.isNotEmpty() }

        logger.info(
            "resolve: originCookie={}, allowedOrigins={}, redirectUriCookieName={}, redirectUriPresent={}",
            origin,
            allowedOrigins,
            redirectUriCookieName,
            !redirectUri.isNullOrBlank(),
        )

        val validOrigin = resolveValidOrigin(origin, allowedOrigins)
        val redirectUrl =
            resolveRedirectUrl(
                redirectUri = redirectUri,
                validOrigin = validOrigin,
                allowedOrigins = allowedOrigins,
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

    private fun resolveValidOrigin(
        origin: String?,
        allowedOrigins: List<String>,
    ): String {
        if (origin != null && isOriginAllowed(origin, allowedOrigins)) {
            return origin
        }

        logger.warn(
            "resolve: origin not in allowedOrigins, falling back. origin={}, allowedOrigins={}",
            origin,
            allowedOrigins,
        )
        return allowedOrigins.firstOrNull { parseOrigin(it) != null } ?: DEFAULT_ORIGIN
    }

    private fun resolveRedirectUrl(
        redirectUri: String?,
        validOrigin: String,
        allowedOrigins: List<String>,
    ): String? {
        val targetUri = redirectUri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (isInternalPath(targetUri)) {
            return buildRedirectUrl(validOrigin, targetUri)
        }

        return resolveAllowedAbsoluteUrl(targetUri, allowedOrigins)
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

    private fun resolveAllowedAbsoluteUrl(
        redirectUri: String,
        allowedOrigins: List<String>,
    ): String? {
        return try {
            val uri = URI(redirectUri)
            val origin = uri.toOrigin()
            if (origin != null && isOriginAllowed(origin, allowedOrigins)) {
                redirectUri
            } else {
                logger.warn(
                    "resolve: redirectUri origin not allowed. redirectOrigin={}, allowedOrigins={}",
                    origin,
                    allowedOrigins,
                )
                null
            }
        } catch (e: Exception) {
            logger.warn("resolve: invalid redirectUri={}", redirectUri)
            null
        }
    }

    private fun isOriginAllowed(
        origin: String,
        allowedOrigins: List<String>,
    ): Boolean {
        val actualOrigin = parseOrigin(origin) ?: return false
        return allowedOrigins.any { allowedOrigin ->
            matchesAllowedOrigin(actualOrigin, allowedOrigin)
        }
    }

    private fun matchesAllowedOrigin(
        actualOrigin: Origin,
        allowedOrigin: String,
    ): Boolean {
        val isWildcard = allowedOrigin.substringAfter("://", "").startsWith("*.")
        if (!isWildcard) {
            return parseOrigin(allowedOrigin) == actualOrigin
        }
        if (allowedOrigin.count { it == '*' } != 1) {
            return false
        }

        val patternOrigin =
            parseOrigin(
                allowedOrigin.replaceFirst("*.", "$WILDCARD_HOST_PLACEHOLDER."),
            ) ?: return false
        val baseHost = patternOrigin.host.removePrefix("$WILDCARD_HOST_PLACEHOLDER.")

        return actualOrigin.scheme == patternOrigin.scheme &&
            actualOrigin.port == patternOrigin.port &&
            actualOrigin.host.endsWith(".$baseHost")
    }

    private fun parseOrigin(value: String): Origin? {
        return try {
            val uri = URI(value)
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
            if (!uri.path.isNullOrEmpty() && uri.path != "/") return null

            Origin(
                scheme = scheme,
                host = host,
                port = uri.normalizedPort(scheme),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun URI.toOrigin(): String? {
        val scheme = this.scheme ?: return null
        val host = this.host ?: return null
        val port = if (this.port != -1) ":${this.port}" else ""
        return "$scheme://$host$port".trimEnd('/')
    }

    private fun URI.normalizedPort(scheme: String): Int {
        if (port != -1) return port
        return when (scheme) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
    }

    private data class Origin(
        val scheme: String,
        val host: String,
        val port: Int,
    )
}
