package com.techtaurant.mainserver.security.helper

import com.techtaurant.mainserver.security.config.CookieProperties
import com.techtaurant.mainserver.security.jwt.JwtConstants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.mock.web.MockHttpServletResponse
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.time.Duration

class CookieHelperTest {
    private lateinit var cookieHelper: CookieHelper

    @BeforeEach
    fun setUp() {
        cookieHelper =
            CookieHelper(
                CookieProperties(
                    secure = true,
                    httpOnly = true,
                    sameSite = "Lax",
                    path = "/",
                ),
            )
    }

    @Test
    @DisplayName("일반 API에는 accessToken만 전송하고 재발급 API에는 두 인증 쿠키를 전송한다")
    fun cookiePathLimitsRefreshTokenRequestHeader() {
        // given
        val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        val legacyResponse = MockHttpServletResponse()
        addLegacyRefreshTokenCookie(legacyResponse)
        storeSetCookies(cookieManager, LOGIN_URI, legacyResponse)

        val response = MockHttpServletResponse()
        cookieHelper.addCookie(response, JwtConstants.ACCESS_TOKEN_COOKIE, "access-token", 3600)
        cookieHelper.addCookie(response, JwtConstants.REFRESH_TOKEN_COOKIE, "refresh-token", 604800)

        // when
        storeSetCookies(cookieManager, LOGIN_URI, response)
        val generalApiCookieHeader = getCookieHeader(cookieManager, GENERAL_API_URI)
        val refreshApiCookieHeader = getCookieHeader(cookieManager, REFRESH_API_URI)

        // then
        assertThat(generalApiCookieHeader)
            .contains("accessToken=access-token")
            .doesNotContain("refreshToken")
        assertThat(refreshApiCookieHeader)
            .contains("accessToken=access-token")
            .contains("refreshToken=refresh-token")
            .doesNotContain("legacy-refresh-token")

        val setCookieHeaders = response.getHeaders(HttpHeaders.SET_COOKIE)
        assertThat(setCookieHeaders).allSatisfy { header ->
            assertThat(header)
                .contains("SameSite=Lax")
                .contains("Max-Age=")
                .contains("Expires=")
        }
        assertThat(setCookieHeaders.single { it.startsWith("accessToken=") }).contains("Path=/;")
        assertThat(
            setCookieHeaders.single {
                it.startsWith("refreshToken=refresh-token")
            },
        )
            .contains("Path=$REFRESH_TOKEN_PATH;")
        assertThat(
            setCookieHeaders.single {
                it.startsWith("refreshToken=") && it.contains("Max-Age=0") && it.contains("Path=/;")
            },
        ).isNotNull()
    }

    @Test
    @DisplayName("재발급 응답은 refreshToken을 동일한 제한 Path에서 갱신한다")
    fun refreshResponseUpdatesRefreshTokenAtRestrictedPath() {
        // given
        val loginResponse = MockHttpServletResponse()
        cookieHelper.addCookie(loginResponse, JwtConstants.ACCESS_TOKEN_COOKIE, "old-access-token", 3600)
        cookieHelper.addCookie(loginResponse, JwtConstants.REFRESH_TOKEN_COOKIE, "old-refresh-token", 604800)
        val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        storeSetCookies(cookieManager, LOGIN_URI, loginResponse)

        val refreshResponse = MockHttpServletResponse()
        cookieHelper.addCookie(refreshResponse, JwtConstants.ACCESS_TOKEN_COOKIE, "new-access-token", 3600)
        cookieHelper.addCookie(refreshResponse, JwtConstants.REFRESH_TOKEN_COOKIE, "new-refresh-token", 604800)

        // when
        storeSetCookies(cookieManager, REFRESH_API_URI, refreshResponse)
        val generalApiCookieHeader = getCookieHeader(cookieManager, GENERAL_API_URI)
        val refreshApiCookieHeader = getCookieHeader(cookieManager, REFRESH_API_URI)

        // then
        assertThat(generalApiCookieHeader)
            .contains("accessToken=new-access-token")
            .doesNotContain("refreshToken")
        assertThat(refreshApiCookieHeader)
            .contains("accessToken=new-access-token")
            .contains("refreshToken=new-refresh-token")
            .doesNotContain("old-refresh-token")
        assertThat(
            refreshResponse.getHeaders(HttpHeaders.SET_COOKIE).single {
                it.startsWith("refreshToken=new-refresh-token")
            },
        )
            .contains("Path=$REFRESH_TOKEN_PATH;")
    }

    @Test
    @DisplayName("로그아웃 응답은 각 인증 쿠키를 생성할 때와 동일한 Path로 삭제한다")
    fun deleteAllAuthCookiesUsesOriginalCookiePaths() {
        // given
        val loginResponse = MockHttpServletResponse()
        cookieHelper.addCookie(loginResponse, JwtConstants.ACCESS_TOKEN_COOKIE, "access-token", 3600)
        cookieHelper.addCookie(loginResponse, JwtConstants.REFRESH_TOKEN_COOKIE, "refresh-token", 604800)
        val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        storeSetCookies(cookieManager, LOGIN_URI, loginResponse)
        val logoutResponse = MockHttpServletResponse()

        // when
        cookieHelper.deleteAllAuthCookies(logoutResponse)
        storeSetCookies(cookieManager, LOGOUT_API_URI, logoutResponse)

        // then
        val deletionHeaders = logoutResponse.getHeaders(HttpHeaders.SET_COOKIE)
        assertThat(deletionHeaders.single { it.startsWith("accessToken=") })
            .contains("Path=/;")
            .contains("Max-Age=0")
        assertThat(
            deletionHeaders.single {
                it.startsWith("refreshToken=") &&
                    it.contains("Path=$REFRESH_TOKEN_PATH;")
            },
        )
            .contains("Path=$REFRESH_TOKEN_PATH;")
            .contains("Max-Age=0")
        assertThat(
            deletionHeaders.single {
                it.startsWith("refreshToken=") &&
                    it.contains("Path=/;")
            },
        ).contains("Max-Age=0")
        assertThat(getCookieHeader(cookieManager, GENERAL_API_URI)).isEmpty()
        assertThat(getCookieHeader(cookieManager, REFRESH_API_URI)).isEmpty()
    }

    private fun addLegacyRefreshTokenCookie(response: MockHttpServletResponse) {
        val legacyCookie =
            ResponseCookie.from(JwtConstants.REFRESH_TOKEN_COOKIE, "legacy-refresh-token")
                .maxAge(Duration.ofDays(7))
                .path("/")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .build()
        response.addHeader(HttpHeaders.SET_COOKIE, legacyCookie.toString())
    }

    private fun storeSetCookies(
        cookieManager: CookieManager,
        responseUri: URI,
        response: MockHttpServletResponse,
    ) {
        response.getHeaders(HttpHeaders.SET_COOKIE).forEach { setCookieHeader ->
            cookieManager.put(
                responseUri,
                mapOf(HttpHeaders.SET_COOKIE to listOf(setCookieHeader)),
            )
        }
    }

    private fun getCookieHeader(
        cookieManager: CookieManager,
        requestUri: URI,
    ): String {
        return cookieManager.get(requestUri, emptyMap())[HttpHeaders.COOKIE]
            ?.joinToString("; ")
            .orEmpty()
    }

    companion object {
        private const val REFRESH_TOKEN_PATH = "/open-api/auth/refresh"
        private val LOGIN_URI = URI("https://api.techtaurant.com/oauth2/callback/google")
        private val GENERAL_API_URI = URI("https://api.techtaurant.com/api/users/me")
        private val REFRESH_API_URI = URI("https://api.techtaurant.com$REFRESH_TOKEN_PATH")
        private val LOGOUT_API_URI = URI("https://api.techtaurant.com/api/auth/logout")
    }
}
