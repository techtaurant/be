package com.techtaurant.mainserver.security.jwt

import com.fasterxml.jackson.databind.ObjectMapper
import com.techtaurant.mainserver.security.handler.CustomAuthenticationEntryPoint
import com.techtaurant.mainserver.user.infrastructure.out.UserTokenRepository
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.MalformedJwtException
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder

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
        val request = requestWithAccessToken(OPTIONAL_AUTH_PATH, EXPIRED_TOKEN)
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
        val request = requestWithAccessToken(REFRESH_PATH, EXPIRED_TOKEN)
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
        val request = requestWithAccessToken(DEV_LOGIN_PATH, EXPIRED_TOKEN)
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
        val request = requestWithAccessToken(OPTIONAL_AUTH_PATH, MALFORMED_TOKEN)
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
        val request = requestWithAccessToken(PROTECTED_PATH, EXPIRED_TOKEN)
        val response = MockHttpServletResponse()
        val filterChain = MockFilterChain()
        givenTokenIsExpired(EXPIRED_TOKEN)

        // when
        jwtAuthenticationFilter.doFilter(request, response, filterChain)

        // then
        assertThat(filterChain.request).isNotNull()
        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK)
    }

    private fun requestWithAccessToken(
        path: String,
        token: String,
    ): MockHttpServletRequest {
        return MockHttpServletRequest("GET", path).apply {
            addHeader("Authorization", "${JwtConstants.BEARER_PREFIX}$token")
        }
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
    }
}
