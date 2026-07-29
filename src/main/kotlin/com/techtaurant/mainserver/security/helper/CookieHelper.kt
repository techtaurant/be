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
        deleteLegacyRefreshTokenCookie(response, name)
        addCookieHeader(response, name, value, maxAge, resolveCookiePath(name))
    }

    fun deleteCookie(
        response: HttpServletResponse,
        name: String,
    ) {
        addCookieHeader(response, name, "", 0, resolveCookiePath(name))
        deleteLegacyRefreshTokenCookie(response, name)
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
    ) {
        val cookie =
            ResponseCookie.from(name, value)
                .maxAge(Duration.ofSeconds(maxAge.toLong()))
                .path(path)
                .httpOnly(cookieProperties.httpOnly)
                .secure(cookieProperties.secure)
                .sameSite(cookieProperties.sameSite)
                .build()

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
    }

    private fun deleteLegacyRefreshTokenCookie(
        response: HttpServletResponse,
        name: String,
    ) {
        if (name == JwtConstants.REFRESH_TOKEN_COOKIE && cookieProperties.path != REFRESH_TOKEN_PATH) {
            addCookieHeader(response, name, "", 0, cookieProperties.path)
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
    }
}
