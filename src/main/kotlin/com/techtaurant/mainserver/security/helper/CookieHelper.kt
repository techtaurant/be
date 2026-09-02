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
     * 브라우저는 Domain·Path 조합이 다르면 이름이 같은 쿠키를 각각 보관해 한 요청에 함께 싣고,
     * RFC 6265는 서버가 그 순서에 기대지 말라고 정합니다.
     * 어느 쪽이 쓸 수 있는 값인지는 호출부만 알 수 있으므로 후보를 모두 돌려줍니다.
     */
    fun getCookies(
        request: HttpServletRequest,
        name: String,
    ): List<String> {
        return request.cookies?.filter { it.name == name }?.map { it.value }.orEmpty()
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

        if (name == JwtConstants.REFRESH_TOKEN_COOKIE) {
            staleRefreshTokenPaths().forEach { stalePath ->
                addCookieHeader(response, name, "", 0, stalePath, domain = null)
            }
        }
    }

    /**
     * refreshToken은 Domain 없이 Path만 옮겨 다녔으므로 지금 쓰지 않는 옛 Path 조합을 함께 만료시킵니다.
     * 전체 경로로 발급되던 시절의 쿠키와, 재발급 경로 하나로만 좁혔던 시절의 쿠키가 대상입니다.
     */
    private fun staleRefreshTokenPaths(): List<String> {
        return listOf(cookieProperties.path, LEGACY_REFRESH_ONLY_TOKEN_PATH)
            .distinct()
            .filter { it != REFRESH_TOKEN_PATH }
    }

    private fun resolveCookiePath(name: String): String {
        return when {
            name == JwtConstants.REFRESH_TOKEN_COOKIE -> REFRESH_TOKEN_PATH
            name.startsWith(OAUTH2_COOKIE_NAME_PREFIX) -> OAUTH2_COOKIE_PATH
            else -> cookieProperties.path
        }
    }

    private fun authCookieMaxAgeSeconds(): Int {
        return Duration.ofMillis(jwtProperties.refreshTokenExpireMs).seconds.toInt()
    }

    private companion object {
        // 재발급과 로그아웃이 모두 이 아래에 있어야 브라우저가 두 요청에 refreshToken을 싣습니다.
        const val REFRESH_TOKEN_PATH = "/open-api/auth"
        const val LEGACY_REFRESH_ONLY_TOKEN_PATH = "/open-api/auth/refresh"
        const val ACCESS_TOKEN_COOKIE_DOMAIN = ".techtaurant.com"

        /**
         * oauth2_ 로 시작하는 쿠키는 OAuth 콜백 요청 한 번에서만 읽습니다.
         * 그 경로로 좁혀야 로그인 진행 중에도 일반 API 요청에 딸려 가지 않습니다.
         * 쿠키를 심는 곳은 authorization 요청이지만 Path는 읽는 곳 기준으로 정합니다.
         */
        const val OAUTH2_COOKIE_NAME_PREFIX = "oauth2_"
        const val OAUTH2_COOKIE_PATH = "/login/oauth2"
    }
}
