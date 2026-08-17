package com.techtaurant.mainserver.security.helper

import com.techtaurant.mainserver.security.config.CookieProperties
import com.techtaurant.mainserver.security.jwt.JwtConstants
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class CookieHelper(
    private val cookieProperties: CookieProperties,
) {
    fun addCookie(
        request: HttpServletRequest,
        response: HttpServletResponse,
        name: String,
        value: String,
        maxAge: Int,
    ) {
        val domain = resolveCookieDomain(request, name)
        expireOtherCookieVariants(response, name, domain)
        addCookieHeader(response, name, value, maxAge, resolveCookiePath(name), domain)
    }

    fun deleteCookie(
        response: HttpServletResponse,
        name: String,
    ) {
        addCookieHeader(response, name, "", 0, resolveCookiePath(name), domain = null)
        expireOtherCookieVariants(response, name, currentDomain = null)
    }

    fun deleteAllAuthCookies(response: HttpServletResponse) {
        deleteCookie(response, JwtConstants.REFRESH_TOKEN_COOKIE)
        deleteCookie(response, JwtConstants.ACCESS_TOKEN_COOKIE)
    }

    fun getCookie(
        request: HttpServletRequest,
        name: String,
    ): String? {
        return request.cookies?.firstOrNull { it.name == name }?.value
    }

    private fun addCookieHeader(
        response: HttpServletResponse,
        name: String,
        value: String,
        maxAge: Int,
        path: String,
        domain: String?,
    ) {
        val cookie =
            ResponseCookie.from(name, value)
                .maxAge(Duration.ofSeconds(maxAge.toLong()))
                .path(path)
                .domain(domain)
                .httpOnly(cookieProperties.httpOnly)
                .secure(cookieProperties.secure)
                .sameSite(cookieProperties.sameSite)
                .build()

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
    }

    /**
     * 프론트엔드 서버가 자신의 서브도메인에서 읽어야 하는 accessToken에만 부모 도메인을 부여한다.
     * refreshToken과 OAuth 쿠키는 API 호스트 밖으로 나갈 이유가 없어 host-only로 둔다.
     * techtaurant.com 하위가 아닌 호스트에서는 브라우저가 부모 도메인 쿠키를 거부하므로 Domain을 생략한다.
     */
    private fun resolveCookieDomain(
        request: HttpServletRequest,
        name: String,
    ): String? {
        if (name != JwtConstants.ACCESS_TOKEN_COOKIE) {
            return null
        }

        return ACCESS_TOKEN_COOKIE_DOMAIN.takeIf { isAccessTokenCookieDomainHost(request.serverName) }
    }

    private fun isAccessTokenCookieDomainHost(host: String): Boolean {
        return host == ACCESS_TOKEN_COOKIE_DOMAIN.removePrefix(".") ||
            host.endsWith(ACCESS_TOKEN_COOKIE_DOMAIN)
    }

    /**
     * 브라우저는 이름이 같아도 Domain·Path 조합이 다르면 서로 다른 쿠키로 취급한다.
     * 두 조합이 함께 남으면 먼저 만들어진 옛 쿠키가 요청 헤더 앞에 실려 옛 토큰으로 인증이 처리되므로,
     * 지금 사용하지 않는 조합을 함께 만료시켜 한 이름당 하나만 남게 한다.
     */
    private fun expireOtherCookieVariants(
        response: HttpServletResponse,
        name: String,
        currentDomain: String?,
    ) {
        if (name == JwtConstants.ACCESS_TOKEN_COOKIE) {
            val otherDomain = if (currentDomain == null) ACCESS_TOKEN_COOKIE_DOMAIN else null
            addCookieHeader(response, name, "", 0, resolveCookiePath(name), otherDomain)
        }

        if (name == JwtConstants.REFRESH_TOKEN_COOKIE && cookieProperties.path != REFRESH_TOKEN_PATH) {
            addCookieHeader(response, name, "", 0, cookieProperties.path, domain = null)
        }
    }

    private fun resolveCookiePath(name: String): String {
        return if (name == JwtConstants.REFRESH_TOKEN_COOKIE) {
            REFRESH_TOKEN_PATH
        } else {
            cookieProperties.path
        }
    }

    private companion object {
        const val REFRESH_TOKEN_PATH = "/open-api/auth/refresh"
        const val ACCESS_TOKEN_COOKIE_DOMAIN = ".techtaurant.com"
    }
}
