package com.techtaurant.mainserver.security.jwt

import com.techtaurant.mainserver.user.enums.UserRole
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
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
