package com.techtaurant.mainserver.security.jwt

import com.fasterxml.jackson.databind.ObjectMapper
import com.techtaurant.mainserver.security.config.CookieProperties
import com.techtaurant.mainserver.security.handler.CustomAuthenticationEntryPoint
import com.techtaurant.mainserver.security.helper.CookieHelper
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserTokenRepository
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.MalformedJwtException
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

class JwtAuthenticationFilterTest {
    private val jwtTokenProvider: JwtTokenProvider = mockk()
    private val userTokenRepository: UserTokenRepository = mockk()
    private lateinit var jwtAuthenticationFilter: JwtAuthenticationFilter

    @BeforeEach
    fun setUp() {
        jwtAuthenticationFilter =
            JwtAuthenticationFilter(
                jwtTokenProvider,
                userTokenRepository,
                CustomAuthenticationEntryPoint(ObjectMapper()),
                CookieHelper(CookieProperties(), JwtProperties(TEST_SECRET)),
            )
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    @DisplayName("인증 없이도 응답하는 경로에서 accessToken이 만료되면 재발급을 요청할 수 있도록 401로 알려준다")
    fun expiredTokenOnOptionalAuthPathIsReportedAsUnauthorized() {
        // given
        val request = requestWithAccessTokenCookies(OPTIONAL_AUTH_PATH, EXPIRED_TOKEN)
        val response = MockHttpServletResponse()
        val filterChain = MockFilterChain()
        givenTokenIsExpired(EXPIRED_TOKEN)

        // when
        jwtAuthenticationFilter.doFilter(request, response, filterChain)

        // then
        assertThat(response.status).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED)
        assertThat(customStatusCodeOf(response)).isEqualTo(JwtStatus.ACCESS_TOKEN_EXPIRED.getCustomStatusCode())
        assertThat(filterChain.request).isNull()
    }

    @Test
    @DisplayName("인증 없이도 응답하는 경로에 토큰이 없으면 비로그인 사용자로 요청을 그대로 통과시킨다")
    fun missingTokenOnOptionalAuthPathPassesThroughAsAnonymous() {
        // given
        val request = MockHttpServletRequest("GET", OPTIONAL_AUTH_PATH)
        val response = MockHttpServletResponse()
        val filterChain = MockFilterChain()

        // when
        jwtAuthenticationFilter.doFilter(request, response, filterChain)

        // then
        assertThat(filterChain.request).isNotNull()
        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    @DisplayName("재발급 경로는 만료된 accessToken을 들고 오는 것이 정상이므로 401로 막지 않는다")
    fun expiredTokenOnRefreshPathIsNotBlocked() {
        // given
        val request = requestWithAccessTokenCookies(REFRESH_PATH, EXPIRED_TOKEN)
        val response = MockHttpServletResponse()
        val filterChain = MockFilterChain()
        givenTokenIsExpired(EXPIRED_TOKEN)

        // when
        jwtAuthenticationFilter.doFilter(request, response, filterChain)

        // then
        assertThat(filterChain.request).isNotNull()
        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK)
    }

    @Test
    @DisplayName("개발용 로그인 경로도 만료된 accessToken을 들고 온 재로그인을 막지 않는다")
    fun expiredTokenOnDevLoginPathIsNotBlocked() {
        // given
        val request = requestWithAccessTokenCookies(DEV_LOGIN_PATH, EXPIRED_TOKEN)
        val response = MockHttpServletResponse()
        val filterChain = MockFilterChain()
        givenTokenIsExpired(EXPIRED_TOKEN)

        // when
        jwtAuthenticationFilter.doFilter(request, response, filterChain)

        // then
        assertThat(filterChain.request).isNotNull()
        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK)
    }

    @Test
    @DisplayName("재발급으로 되살릴 수 없는 손상된 토큰은 비로그인 사용자로 통과시켜 콘텐츠를 계속 보여준다")
    fun malformedTokenOnOptionalAuthPathPassesThroughAsAnonymous() {
        // given
        val request = requestWithAccessTokenCookies(OPTIONAL_AUTH_PATH, MALFORMED_TOKEN)
        val response = MockHttpServletResponse()
        val filterChain = MockFilterChain()
        every { jwtTokenProvider.validateAndGetClaims(MALFORMED_TOKEN) } throws MalformedJwtException("malformed")

        // when
        jwtAuthenticationFilter.doFilter(request, response, filterChain)

        // then
        assertThat(filterChain.request).isNotNull()
        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    @DisplayName("인증이 필요한 경로는 인가 계층이 이미 401을 내려주므로 필터가 직접 응답하지 않는다")
    fun expiredTokenOnProtectedPathIsLeftToAuthorizationLayer() {
        // given
        val request = requestWithAccessTokenCookies(PROTECTED_PATH, EXPIRED_TOKEN)
        val response = MockHttpServletResponse()
        val filterChain = MockFilterChain()
        givenTokenIsExpired(EXPIRED_TOKEN)

        // when
        jwtAuthenticationFilter.doFilter(request, response, filterChain)

        // then
        assertThat(filterChain.request).isNotNull()
        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK)
    }

    @Test
    @DisplayName("이름이 같은 accessToken 쿠키가 여러 개 실려 와도 아직 만료되지 않은 토큰을 찾아 인증한다")
    fun usableAccessTokenCookieIsFoundAmongDuplicates() {
        // given
        val request = requestWithAccessTokenCookies(OPTIONAL_AUTH_PATH, EXPIRED_TOKEN, VALID_TOKEN)
        val response = MockHttpServletResponse()
        val filterChain = MockFilterChain()
        givenTokenIsExpired(EXPIRED_TOKEN)
        givenTokenBelongsTo(VALID_TOKEN, AUTHENTICATED_USER_ID)

        // when
        jwtAuthenticationFilter.doFilter(request, response, filterChain)

        // then
        assertThat(SecurityContextHolder.getContext().authentication?.principal).isEqualTo(AUTHENTICATED_USER_ID)
        assertThat(filterChain.request).isNotNull()
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty()
    }

    @Test
    @DisplayName("쿠키로 온 accessToken이 모두 만료됐으면 남은 Domain 조합까지 함께 지워 다음 요청이 같은 토큰을 다시 싣지 않게 한다")
    fun expiredAccessTokenCookiesAreClearedInEveryDomainVariant() {
        // given
        val request = requestWithAccessTokenCookies(OPTIONAL_AUTH_PATH, EXPIRED_TOKEN)
        val response = MockHttpServletResponse()
        val filterChain = MockFilterChain()
        givenTokenIsExpired(EXPIRED_TOKEN)

        // when
        jwtAuthenticationFilter.doFilter(request, response, filterChain)

        // then
        val setCookieHeaders = response.getHeaders(HttpHeaders.SET_COOKIE)
        assertThat(setCookieHeaders).hasSize(2)
        assertThat(setCookieHeaders).allMatch { it.startsWith("${JwtConstants.ACCESS_TOKEN_COOKIE}=;") && it.contains("Max-Age=0") }
        assertThat(setCookieHeaders.filter { it.contains("Domain=$ACCESS_TOKEN_COOKIE_DOMAIN") }).hasSize(1)
        assertThat(setCookieHeaders).noneMatch { it.contains(JwtConstants.REFRESH_TOKEN_COOKIE) }
    }

    @Test
    @DisplayName("토큰을 새로 발급하는 경로는 같은 응답에서 쿠키를 내려주므로 만료된 accessToken 쿠키를 지우지 않는다")
    fun expiredAccessTokenCookieIsKeptOnTokenIssuingPath() {
        // given
        val request = requestWithAccessTokenCookies(REFRESH_PATH, EXPIRED_TOKEN)
        val response = MockHttpServletResponse()
        val filterChain = MockFilterChain()
        givenTokenIsExpired(EXPIRED_TOKEN)

        // when
        jwtAuthenticationFilter.doFilter(request, response, filterChain)

        // then
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty()
        assertThat(filterChain.request).isNotNull()
    }

    @Test
    @DisplayName("더는 인정하지 않는 토큰은 쿠키를 지우고 INVALID_TOKEN으로 알려 재로그인하게 한다")
    fun rejectedAccessTokenCookieIsClearedAndReported() {
        // given
        val request = requestWithAccessTokenCookies(OPTIONAL_AUTH_PATH, LEGACY_TOKEN)
        val response = MockHttpServletResponse()
        val filterChain = MockFilterChain()
        every {
            jwtTokenProvider.validateAndGetClaims(LEGACY_TOKEN)
        } throws IllegalArgumentException("허용되지 않는 토큰 종류입니다")

        // when
        jwtAuthenticationFilter.doFilter(request, response, filterChain)

        // then
        assertThat(response.status).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED)
        assertThat(customStatusCodeOf(response)).isEqualTo(JwtStatus.INVALID_TOKEN.getCustomStatusCode())
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).hasSize(2)
        assertThat(filterChain.request).isNull()
    }

    private fun requestWithAccessTokenCookies(
        path: String,
        vararg tokens: String,
    ): MockHttpServletRequest {
        return MockHttpServletRequest("GET", path).apply {
            setCookies(*tokens.map { Cookie(JwtConstants.ACCESS_TOKEN_COOKIE, it) }.toTypedArray())
        }
    }

    private fun givenTokenBelongsTo(
        token: String,
        userId: UUID,
    ) {
        every {
            jwtTokenProvider.validateAndGetClaims(token)
        } returns JwtClaims(userId, UserRole.USER.key, JwtConstants.EXPIRING_ACCESS_TOKEN_IS_PERMANENT)
    }

    private fun givenTokenIsExpired(token: String) {
        every {
            jwtTokenProvider.validateAndGetClaims(token)
        } throws ExpiredJwtException(null, null, "expired")
    }

    private fun customStatusCodeOf(response: MockHttpServletResponse): Int {
        return ObjectMapper().readTree(response.contentAsString).get("status").asInt()
    }

    companion object {
        private const val OPTIONAL_AUTH_PATH = "/open-api/posts"
        private const val REFRESH_PATH = "/open-api/auth/refresh"
        private const val DEV_LOGIN_PATH = "/open-api/dev/auth/login"
        private const val PROTECTED_PATH = "/api/users/me"
        private const val EXPIRED_TOKEN = "expired-access-token"
        private const val MALFORMED_TOKEN = "malformed-access-token"
        private const val VALID_TOKEN = "valid-access-token"
        private const val LEGACY_TOKEN = "legacy-access-token"
        private const val TEST_SECRET = "test-secret-key-for-jwt-properties-in-filter-unit-test"
        private const val ACCESS_TOKEN_COOKIE_DOMAIN = ".techtaurant.com"
        private val AUTHENTICATED_USER_ID: UUID = UUID.randomUUID()
    }
}
