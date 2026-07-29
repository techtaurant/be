package com.techtaurant.mainserver.security.infrastructure.`in`

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.security.cache.TokenCachePort
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtProperties
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.enums.UserRole
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import java.time.Instant
import java.util.Date
import java.util.UUID

@DisplayName("AuthApiController 통합 테스트")
class AuthApiControllerIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var jwtProperties: JwtProperties

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    private lateinit var tokenCachePort: TokenCachePort

    @Test
    @DisplayName("만료된 accessToken 쿠키로도 로그아웃하여 서버 토큰과 인증 쿠키를 폐기한다")
    fun expiredAccessTokenCookieLogsOut() {
        val userId = UUID.randomUUID()
        val expiredAccessToken = createExpiredAccessToken(userId)
        tokenCachePort.saveRefreshToken(userId.toString(), "refresh-token")

        val response =
            given()
                .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, expiredAccessToken)
                .`when`()
                .post("/api/auth/logout")

        response.then().statusCode(HttpStatus.OK.value())
        assertThat(tokenCachePort.getRefreshToken(userId.toString())).isNull()
        val setCookieHeaders = response.headers.getValues(HttpHeaders.SET_COOKIE)
        assertThat(
            setCookieHeaders.any {
                it.startsWith("${JwtConstants.ACCESS_TOKEN_COOKIE}=") &&
                    it.contains("Max-Age=0") &&
                    it.contains("Path=/")
            },
        ).isTrue()
        assertThat(
            setCookieHeaders.any {
                it.startsWith("${JwtConstants.REFRESH_TOKEN_COOKIE}=") &&
                    it.contains("Max-Age=0") &&
                    it.contains("Path=/open-api/auth/refresh")
            },
        ).isTrue()
    }

    @Test
    @DisplayName("bearer 인증 사용자와 accessToken 쿠키 사용자가 다르면 인증 사용자의 서버 토큰만 폐기한다")
    fun authenticatedPrincipalTakesPrecedenceOverCookieSubject() {
        val authenticatedUserId = UUID.randomUUID()
        val cookieUserId = UUID.randomUUID()
        val bearerAccessToken = jwtTokenProvider.createAccessToken(authenticatedUserId, UserRole.USER)
        val cookieAccessToken = jwtTokenProvider.createAccessToken(cookieUserId, UserRole.USER)
        tokenCachePort.saveRefreshToken(authenticatedUserId.toString(), "authenticated-refresh-token")
        tokenCachePort.saveRefreshToken(cookieUserId.toString(), "cookie-refresh-token")

        given()
            .header(HttpHeaders.AUTHORIZATION, "${JwtConstants.BEARER_PREFIX}$bearerAccessToken")
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, cookieAccessToken)
            .`when`()
            .post("/api/auth/logout")
            .then()
            .statusCode(HttpStatus.OK.value())

        assertThat(tokenCachePort.getRefreshToken(authenticatedUserId.toString())).isNull()
        assertThat(tokenCachePort.getRefreshToken(cookieUserId.toString())).isEqualTo("cookie-refresh-token")
    }

    private fun createExpiredAccessToken(userId: UUID): String {
        val now = Instant.now()
        return Jwts.builder()
            .subject(userId.toString())
            .claim(JwtConstants.ROLE_CLAIM, UserRole.USER.key)
            .claim(JwtConstants.PERMANENT_CLAIM, JwtConstants.EXPIRING_ACCESS_TOKEN_IS_PERMANENT)
            .issuedAt(Date.from(now.minusSeconds(2)))
            .expiration(Date.from(now.minusSeconds(1)))
            .signWith(Keys.hmacShaKeyFor(jwtProperties.secret.toByteArray()))
            .compact()
    }
}
