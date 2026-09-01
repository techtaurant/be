package com.techtaurant.mainserver.security.helper

import com.techtaurant.mainserver.security.config.CookieProperties
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.mock.web.MockHttpServletRequest
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
                JwtProperties(
                    secret = "test-secret",
                    accessTokenExpireMs = ACCESS_TOKEN_EXPIRE_MS,
                    refreshTokenExpireMs = REFRESH_TOKEN_EXPIRE_MS,
                ),
            )
    }

    @Test
    @DisplayName("일반 API에는 accessToken만 전송하고 재발급 API에는 두 인증 쿠키를 전송한다")
    fun cookiePathLimitsRefreshTokenRequestHeader() {
        // given
        val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        val legacyResponse = MockHttpServletResponse()
        addLegacyHostOnlyCookie(legacyResponse, JwtConstants.REFRESH_TOKEN_COOKIE, "legacy-refresh-token", "/")
        storeSetCookies(cookieManager, LOGIN_URI, legacyResponse)

        val response = MockHttpServletResponse()
        cookieHelper.addAuthCookie(apiHostRequest(), response, JwtConstants.ACCESS_TOKEN_COOKIE, "access-token")
        cookieHelper.addAuthCookie(apiHostRequest(), response, JwtConstants.REFRESH_TOKEN_COOKIE, "refresh-token")

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
            assertThat(header).contains("SameSite=Lax")
        }
        assertThat(setCookieHeaders.single { it.startsWith("accessToken=access-token") }).contains("Path=/;")
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
    @DisplayName("인증 쿠키 두 개는 모두 refreshToken 수명만큼 살아남고 OAuth 쿠키만 자체 만료 시각을 갖는다")
    fun authCookiesShareRefreshTokenLifetime() {
        // given
        val response = MockHttpServletResponse()

        // when
        cookieHelper.addAuthCookie(apiHostRequest(), response, JwtConstants.ACCESS_TOKEN_COOKIE, "access-token")
        cookieHelper.addAuthCookie(apiHostRequest(), response, JwtConstants.REFRESH_TOKEN_COOKIE, "refresh-token")
        cookieHelper.addCookie(apiHostRequest(), response, OAUTH2_AUTHORIZATION_REQUEST_COOKIE, "oauth-state", 180)

        // then
        val setCookieHeaders = response.getHeaders(HttpHeaders.SET_COOKIE)
        val authCookieHeaders =
            setCookieHeaders.filter {
                it.startsWith("accessToken=access-token") || it.startsWith("refreshToken=refresh-token")
            }
        assertThat(authCookieHeaders).hasSize(2)
        assertThat(authCookieHeaders).allSatisfy { header ->
            assertThat(header)
                .contains("Max-Age=$AUTH_COOKIE_MAX_AGE_SECONDS")
                .contains("Expires=")
        }
        assertThat(setCookieHeaders.single { it.startsWith("$OAUTH2_AUTHORIZATION_REQUEST_COOKIE=oauth-state") })
            .contains("Max-Age=180")
            .contains("Expires=")
    }

    @Test
    @DisplayName("refreshToken은 재발급과 로그아웃 요청에 함께 실리고 그 밖의 API에는 실리지 않는다")
    fun refreshTokenReachesBothAuthEndpoints() {
        // given
        val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        val response = MockHttpServletResponse()
        cookieHelper.addAuthCookie(apiHostRequest(), response, JwtConstants.REFRESH_TOKEN_COOKIE, "refresh-token")

        // when
        storeSetCookies(cookieManager, LOGIN_URI, response)

        // then
        assertThat(getCookieHeader(cookieManager, REFRESH_API_URI)).contains("refreshToken=refresh-token")
        assertThat(getCookieHeader(cookieManager, LOGOUT_API_URI)).contains("refreshToken=refresh-token")
        assertThat(getCookieHeader(cookieManager, GENERAL_API_URI)).doesNotContain("refreshToken")
    }

    @Test
    @DisplayName("재발급 응답은 refreshToken을 동일한 제한 Path에서 갱신한다")
    fun refreshResponseUpdatesRefreshTokenAtRestrictedPath() {
        // given
        val loginResponse = MockHttpServletResponse()
        cookieHelper.addAuthCookie(
            apiHostRequest(),
            loginResponse,
            JwtConstants.ACCESS_TOKEN_COOKIE,
            "old-access-token",
        )
        cookieHelper.addAuthCookie(
            apiHostRequest(),
            loginResponse,
            JwtConstants.REFRESH_TOKEN_COOKIE,
            "old-refresh-token",
        )
        val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        storeSetCookies(cookieManager, LOGIN_URI, loginResponse)

        val refreshResponse = MockHttpServletResponse()
        cookieHelper.addAuthCookie(
            apiHostRequest(),
            refreshResponse,
            JwtConstants.ACCESS_TOKEN_COOKIE,
            "new-access-token",
        )
        cookieHelper.addAuthCookie(
            apiHostRequest(),
            refreshResponse,
            JwtConstants.REFRESH_TOKEN_COOKIE,
            "new-refresh-token",
        )

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
        cookieHelper.addAuthCookie(apiHostRequest(), loginResponse, JwtConstants.ACCESS_TOKEN_COOKIE, "access-token")
        cookieHelper.addAuthCookie(
            apiHostRequest(),
            loginResponse,
            JwtConstants.REFRESH_TOKEN_COOKIE,
            "refresh-token",
        )
        val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        storeSetCookies(cookieManager, LOGIN_URI, loginResponse)
        val logoutResponse = MockHttpServletResponse()

        // when
        cookieHelper.deleteAllAuthCookies(logoutResponse)
        storeSetCookies(cookieManager, LOGOUT_API_URI, logoutResponse)

        // then
        val deletionHeaders = logoutResponse.getHeaders(HttpHeaders.SET_COOKIE)
        assertThat(deletionHeaders).allSatisfy { header ->
            assertThat(header).contains("Max-Age=0")
        }
        assertThat(
            deletionHeaders.single {
                it.startsWith("accessToken=") && it.contains(DOMAIN_ATTRIBUTE)
            },
        ).contains("Path=/;")
        assertThat(
            deletionHeaders.single {
                it.startsWith("refreshToken=") && it.contains("Path=$REFRESH_TOKEN_PATH;")
            },
        ).doesNotContain(DOMAIN_ATTRIBUTE)
        assertThat(getCookieHeader(cookieManager, GENERAL_API_URI)).isEmpty()
        assertThat(getCookieHeader(cookieManager, REFRESH_API_URI)).isEmpty()
    }

    @Test
    @DisplayName("accessToken은 techtaurant.com 하위 도메인인 프론트엔드 서버에서도 읽을 수 있다")
    fun accessTokenIsReadableFromFrontendSubdomain() {
        // given
        val response = MockHttpServletResponse()
        cookieHelper.addAuthCookie(apiHostRequest(), response, JwtConstants.ACCESS_TOKEN_COOKIE, "access-token")
        val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)

        // when
        storeSetCookies(cookieManager, LOGIN_URI, response)
        val frontendCookieHeader = getCookieHeader(cookieManager, FRONTEND_SERVER_URI)

        // then
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE).single { it.startsWith("accessToken=access-token") })
            .contains(DOMAIN_ATTRIBUTE)
        assertThat(frontendCookieHeader).contains("accessToken=access-token")
    }

    @Test
    @DisplayName("refreshToken과 OAuth 쿠키는 API 호스트에만 남아 형제 도메인으로 전송되지 않는다")
    fun refreshTokenAndOauthCookiesStayHostOnly() {
        // given
        val response = MockHttpServletResponse()
        cookieHelper.addAuthCookie(apiHostRequest(), response, JwtConstants.REFRESH_TOKEN_COOKIE, "refresh-token")
        cookieHelper.addCookie(apiHostRequest(), response, OAUTH2_AUTHORIZATION_REQUEST_COOKIE, "oauth-state", 180)
        val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)

        // when
        storeSetCookies(cookieManager, LOGIN_URI, response)
        val frontendRefreshCookieHeader = getCookieHeader(cookieManager, FRONTEND_REFRESH_URI)
        val refreshApiCookieHeader = getCookieHeader(cookieManager, REFRESH_API_URI)

        // then
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).allSatisfy { header ->
            assertThat(header).doesNotContain("Domain=")
        }
        assertThat(frontendRefreshCookieHeader).isEmpty()
        assertThat(refreshApiCookieHeader).contains("refreshToken=refresh-token")
    }

    @Test
    @DisplayName("techtaurant.com 하위가 아닌 호스트에서는 accessToken에 Domain을 붙이지 않는다")
    fun accessTokenOmitsDomainOnNonTechtaurantHost() {
        // given
        val response = MockHttpServletResponse()
        cookieHelper.addAuthCookie(localhostRequest(), response, JwtConstants.ACCESS_TOKEN_COOKIE, "access-token")
        val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)

        // when
        storeSetCookies(cookieManager, LOCAL_LOGIN_URI, response)
        val localApiCookieHeader = getCookieHeader(cookieManager, LOCAL_GENERAL_API_URI)

        // then
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE).single { it.startsWith("accessToken=access-token") })
            .doesNotContain("Domain=")
        assertThat(localApiCookieHeader).contains("accessToken=access-token")
    }

    @Test
    @DisplayName("Domain 없이 발급됐던 이전 accessToken은 새 쿠키를 내려줄 때 함께 만료된다")
    fun addCookieExpiresLegacyHostOnlyAccessToken() {
        // given
        val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        val legacyResponse = MockHttpServletResponse()
        addLegacyHostOnlyCookie(legacyResponse, JwtConstants.ACCESS_TOKEN_COOKIE, "legacy-access-token", "/")
        storeSetCookies(cookieManager, LOGIN_URI, legacyResponse)

        val response = MockHttpServletResponse()
        cookieHelper.addAuthCookie(apiHostRequest(), response, JwtConstants.ACCESS_TOKEN_COOKIE, "access-token")

        // when
        storeSetCookies(cookieManager, LOGIN_URI, response)

        // then
        assertThat(getCookieHeader(cookieManager, GENERAL_API_URI))
            .contains("accessToken=access-token")
            .doesNotContain("legacy-access-token")
    }

    @Test
    @DisplayName("dev API 로그인이 prod accessToken을 덮어쓴다 - 부모 도메인이 두 환경을 한 슬롯으로 합친 현재 동작")
    fun devLoginOverwritesProdAccessTokenSlot() {
        // given
        val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        val prodResponse = MockHttpServletResponse()
        cookieHelper.addAuthCookie(
            apiHostRequest(),
            prodResponse,
            JwtConstants.ACCESS_TOKEN_COOKIE,
            "prod-access-token",
        )
        storeSetCookies(cookieManager, LOGIN_URI, prodResponse)

        val devResponse = MockHttpServletResponse()
        cookieHelper.addAuthCookie(
            devApiHostRequest(),
            devResponse,
            JwtConstants.ACCESS_TOKEN_COOKIE,
            "dev-access-token",
        )

        // when
        storeSetCookies(cookieManager, DEV_LOGIN_URI, devResponse)

        // then
        assertThat(devResponse.getHeaders(HttpHeaders.SET_COOKIE).single { it.startsWith("accessToken=dev-access-token") })
            .contains(DOMAIN_ATTRIBUTE)
        assertThat(getCookieHeader(cookieManager, GENERAL_API_URI))
            .contains("accessToken=dev-access-token")
            .doesNotContain("prod-access-token")
        assertThat(getCookieHeader(cookieManager, DEV_GENERAL_API_URI))
            .contains("accessToken=dev-access-token")
    }

    @Test
    @DisplayName("도메인 쿠키와 host-only 쿠키가 함께 남으면 한 요청에 accessToken 두 개가 실려 어느 토큰이 쓰일지 정해지지 않는다")
    fun coexistingAccessTokenVariantsMakeRequestHeaderAmbiguous() {
        // given
        val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        val domainResponse = MockHttpServletResponse()
        cookieHelper.addAuthCookie(
            apiHostRequest(),
            domainResponse,
            JwtConstants.ACCESS_TOKEN_COOKIE,
            "domain-access-token",
        )
        storeSetCookies(cookieManager, LOGIN_URI, domainResponse)

        val rolledBackResponse = MockHttpServletResponse()
        addLegacyHostOnlyCookie(
            rolledBackResponse,
            JwtConstants.ACCESS_TOKEN_COOKIE,
            "host-only-access-token",
            "/",
        )

        // when
        storeSetCookies(cookieManager, LOGIN_URI, rolledBackResponse)
        val cookieHeader = getCookieHeader(cookieManager, GENERAL_API_URI)

        // then
        assertThat(cookieHeader)
            .contains("accessToken=domain-access-token")
            .contains("accessToken=host-only-access-token")
        assertThat(cookieHeader.split("; ").count { it.startsWith("accessToken=") }).isEqualTo(2)
    }

    private fun apiHostRequest(): MockHttpServletRequest {
        return MockHttpServletRequest().apply { serverName = API_HOST }
    }

    private fun devApiHostRequest(): MockHttpServletRequest {
        return MockHttpServletRequest().apply { serverName = DEV_API_HOST }
    }

    private fun localhostRequest(): MockHttpServletRequest {
        return MockHttpServletRequest().apply { serverName = LOCAL_HOST }
    }

    private fun addLegacyHostOnlyCookie(
        response: MockHttpServletResponse,
        name: String,
        value: String,
        path: String,
    ) {
        val legacyCookie =
            ResponseCookie.from(name, value)
                .maxAge(Duration.ofDays(7))
                .path(path)
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
        private const val ACCESS_TOKEN_EXPIRE_MS = 3_600_000L
        private const val REFRESH_TOKEN_EXPIRE_MS = 604_800_000L
        private const val AUTH_COOKIE_MAX_AGE_SECONDS = 604_800L
        private const val REFRESH_TOKEN_PATH = "/open-api/auth"
        private const val REFRESH_ENDPOINT_PATH = "/open-api/auth/refresh"
        private const val LOGOUT_ENDPOINT_PATH = "/open-api/auth/logout"
        private const val DOMAIN_ATTRIBUTE = "Domain=.techtaurant.com;"
        private const val OAUTH2_AUTHORIZATION_REQUEST_COOKIE = "oauth2_auth_request"
        private const val API_HOST = "api.techtaurant.com"
        private const val DEV_API_HOST = "dev-api.techtaurant.com"
        private const val FRONTEND_HOST = "techtaurant.com"
        private const val LOCAL_HOST = "localhost"
        private val LOGIN_URI = URI("https://$API_HOST/oauth2/callback/google")
        private val DEV_LOGIN_URI = URI("https://$DEV_API_HOST/oauth2/callback/google")
        private val DEV_GENERAL_API_URI = URI("https://$DEV_API_HOST/api/users/me")
        private val FRONTEND_SERVER_URI = URI("https://$FRONTEND_HOST/")
        private val FRONTEND_REFRESH_URI = URI("https://$FRONTEND_HOST$REFRESH_ENDPOINT_PATH")
        private val GENERAL_API_URI = URI("https://$API_HOST/api/users/me")
        private val REFRESH_API_URI = URI("https://$API_HOST$REFRESH_ENDPOINT_PATH")
        private val LOGOUT_API_URI = URI("https://$API_HOST$LOGOUT_ENDPOINT_PATH")
        private val LOCAL_LOGIN_URI = URI("https://$LOCAL_HOST:8080/oauth2/callback/google")
        private val LOCAL_GENERAL_API_URI = URI("https://$LOCAL_HOST:8080/api/users/me")
    }
}
