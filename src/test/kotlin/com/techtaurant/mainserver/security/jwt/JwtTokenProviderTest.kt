package com.techtaurant.mainserver.security.jwt

import com.techtaurant.mainserver.user.enums.UserRole
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Date
import java.util.UUID

@DisplayName("JwtTokenProvider UTC Instant 테스트")
class JwtTokenProviderTest {
    private val properties =
        JwtProperties(
            secret = "test-jwt-secret-key-minimum-256-bits-for-hs256-algorithm",
            accessTokenExpireMs = 3_600_000L,
            refreshTokenExpireMs = 604_800_000L,
        )
    private val provider = JwtTokenProvider(properties)
    private val secretKey = Keys.hmacShaKeyFor(properties.secret.toByteArray())

    @Test
    @DisplayName("Access token은 UTC epoch 기반 iat/exp를 발급하고 claims를 검증할 수 있다")
    fun createAccessToken_usesAbsoluteIssuedAtAndExpiration() {
        val userId = UUID.randomUUID()
        val beforeIssue = System.currentTimeMillis()

        val token = provider.createAccessToken(userId, UserRole.ADMIN)
        val afterIssue = System.currentTimeMillis()

        val parsedClaims =
            Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .payload
        val validatedClaims = provider.validateAndGetClaims(token)

        assertThat(validatedClaims.userId).isEqualTo(userId)
        assertThat(validatedClaims.role).isEqualTo(UserRole.ADMIN.key)
        assertThat(validatedClaims.isPermanent).isFalse()
        assertThat(parsedClaims.issuedAt).isBetween(Date(beforeIssue - 1_000L), Date(afterIssue + 1_000L))
        assertThat(parsedClaims.expiration.time - parsedClaims.issuedAt.time).isEqualTo(properties.accessTokenExpireMs)
    }

    @Test
    @DisplayName("Refresh token은 refresh 전용 검증으로 userId를 돌려준다")
    fun validateAndGetRefreshTokenUserId_acceptsRefreshToken() {
        val userId = UUID.randomUUID()

        val token = provider.createRefreshToken(userId)

        assertThat(provider.validateAndGetRefreshTokenUserId(token)).isEqualTo(userId)
    }

    @Test
    @DisplayName("Refresh token을 accessToken 자리에 넣으면 종류가 달라 거부한다")
    fun validateAndGetClaims_rejectsRefreshToken() {
        val token = provider.createRefreshToken(UUID.randomUUID())

        assertThrows<IllegalArgumentException> { provider.validateAndGetClaims(token) }
    }

    @Test
    @DisplayName("Access token을 refreshToken 자리에 넣으면 종류가 달라 거부한다")
    fun validateAndGetRefreshTokenUserId_rejectsAccessToken() {
        val token = provider.createAccessToken(UUID.randomUUID(), UserRole.USER)

        assertThrows<IllegalArgumentException> { provider.validateAndGetRefreshTokenUserId(token) }
    }

    @Test
    @DisplayName("종류 표시가 없는 예전 토큰은 서명이 유효해도 거부한다")
    fun validateAndGetClaims_rejectsTokenWithoutTypeClaim() {
        val legacyToken =
            Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim(JwtConstants.ROLE_CLAIM, UserRole.USER.key)
                .claim(JwtConstants.PERMANENT_CLAIM, JwtConstants.EXPIRING_ACCESS_TOKEN_IS_PERMANENT)
                .issuedAt(Date())
                .expiration(Date(System.currentTimeMillis() + properties.accessTokenExpireMs))
                .signWith(secretKey)
                .compact()

        assertThrows<IllegalArgumentException> { provider.validateAndGetClaims(legacyToken) }
    }

    @Test
    @DisplayName("Permanent access token은 만료 시간이 없고 permanent claim을 포함한다")
    fun createPermanentAccessToken_hasNoExpirationAndContainsPermanentClaim() {
        val userId = UUID.randomUUID()

        val token = provider.createPermanentAccessToken(userId, UserRole.COMPANY)

        val parsedClaims =
            Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .payload
        val validatedClaims = provider.validateAndGetClaims(token)

        assertThat(validatedClaims.userId).isEqualTo(userId)
        assertThat(validatedClaims.role).isEqualTo(UserRole.COMPANY.key)
        assertThat(validatedClaims.isPermanent).isTrue()
        assertThat(parsedClaims.expiration).isNull()
    }
}
