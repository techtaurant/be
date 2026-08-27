package com.techtaurant.mainserver.security.helper

import com.techtaurant.mainserver.security.config.CookieProperties
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtProperties
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class CookieHelper(
    private val cookieProperties: CookieProperties,
    private val jwtProperties: JwtProperties,
) {
    fun addCookie(
        request: HttpServletRequest,
        response: HttpServletResponse,
        name: String,
        value: String,
        maxAge: Int,
    ) {
        issueCookie(request, response, name, value, maxAge)
    }

    /**
     * 인증 쿠키를 refreshToken 수명만큼 살려 둔다.
     * 쿠키가 accessToken 만료 시각에 함께 사라지면 만료된 토큰이 서버에 도달하지 않아
     * 서버가 토큰 만료와 미인증을 구분해 알려줄 수 없고, 클라이언트도 재발급 시점을 알 수 없다.
     */
    fun addAuthCookie(
        request: HttpServletRequest,
        response: HttpServletResponse,
        name: String,
        value: String,
    ) {
        issueCookie(request, response, name, value, authCookieMaxAgeSeconds())
    }

    /**
     * 발급 시점의 호스트를 알 수 없으므로 host-only 조합과 도메인 조합의 만료 헤더를 모두 내보낸다.
     */
    fun deleteCookie(
        response: HttpServletResponse,
        name: String,
    ) {
        addCookieHeader(response, name, "", 0, resolveCookiePath(name), domain = null)
        expireStaleCookieVariants(response, name, staleAccessTokenDomain = ACCESS_TOKEN_COOKIE_DOMAIN)
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

    /**
     * 쿠키를 발급하면서, 지금 쓰지 않는 옛 Domain·Path 조합의 만료 헤더를 함께 내보낸다.
     */
    private fun issueCookie(
        request: HttpServletRequest,
        response: HttpServletResponse,
        name: String,
        value: String,
        maxAge: Int,
    ) {
        val domain = resolveCookieDomain(request, name)
        val staleAccessTokenDomain = if (domain == null) ACCESS_TOKEN_COOKIE_DOMAIN else null
        expireStaleCookieVariants(response, name, staleAccessTokenDomain)
        addCookieHeader(response, name, value, maxAge, resolveCookiePath(name), domain)
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
     *
     * staleAccessTokenDomain은 호출 경로와 무관하게 언제나 "만료시킬 accessToken 쿠키의 Domain 값"을 뜻한다.
     *
     * 발급 경로에서 host-only 조합을 만료시키는 것은 Domain 부여 이전에 발급된 쿠키를 걷어내기 위한
     * 마이그레이션 조치이므로, 배포 후 accessToken TTL이 지나 롤백 가능성이 닫히면 제거할 수 있다.
     * deleteCookie 경로의 두 조합 만료는 발급 호스트를 알 수 없어 남는 영구 동작이다.
     */
    private fun expireStaleCookieVariants(
        response: HttpServletResponse,
        name: String,
        staleAccessTokenDomain: String?,
    ) {
        if (name == JwtConstants.ACCESS_TOKEN_COOKIE) {
            addCookieHeader(response, name, "", 0, resolveCookiePath(name), staleAccessTokenDomain)
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

    private fun authCookieMaxAgeSeconds(): Int {
        return Duration.ofMillis(jwtProperties.refreshTokenExpireMs).seconds.toInt()
    }

    private companion object {
        const val REFRESH_TOKEN_PATH = "/open-api/auth/refresh"
        const val ACCESS_TOKEN_COOKIE_DOMAIN = ".techtaurant.com"
    }
}
