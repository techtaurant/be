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
        response: HttpServletResponse,
        name: String,
        value: String,
        maxAge: Int,
    ) {
        expireLegacyCookies(response, name)
        addCookieHeader(response, name, value, maxAge, resolveCookiePath(name), AUTH_COOKIE_DOMAIN)
    }

    fun deleteCookie(
        response: HttpServletResponse,
        name: String,
    ) {
        addCookieHeader(response, name, "", 0, resolveCookiePath(name), AUTH_COOKIE_DOMAIN)
        expireLegacyCookies(response, name)
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
     * 이전 버전이 Domain 없이 발급한 host-only 쿠키는 도메인 쿠키를 지우는 헤더로는 사라지지 않는다.
     * 같은 이름의 쿠키가 함께 남으면 먼저 만들어진 옛 쿠키가 요청 헤더 앞에 실려 옛 토큰으로 인증이 처리되므로,
     * 인증 쿠키를 새로 내려주거나 지울 때마다 예전 Path에 남아 있을 수 있는 host-only 쿠키까지 만료시킨다.
     */
    private fun expireLegacyCookies(
        response: HttpServletResponse,
        name: String,
    ) {
        expireHostOnlyCookie(response, name, resolveCookiePath(name))
        if (name == JwtConstants.REFRESH_TOKEN_COOKIE && cookieProperties.path != REFRESH_TOKEN_PATH) {
            expireHostOnlyCookie(response, name, cookieProperties.path)
        }
    }

    private fun expireHostOnlyCookie(
        response: HttpServletResponse,
        name: String,
        path: String,
    ) {
        addCookieHeader(response, name, "", 0, path, domain = null)
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

        // 프론트엔드 서버가 자신의 서브도메인에서 인증 쿠키를 읽을 수 있도록 techtaurant.com 전체를 쿠키 범위로 둔다.
        const val AUTH_COOKIE_DOMAIN = ".techtaurant.com"
    }
}
