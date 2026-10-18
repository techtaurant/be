package com.techtaurant.mainserver.security.interlight

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.user.enums.UserRole
import io.jsonwebtoken.Jwts
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

class InterlightJwtIssuerTest {
    private val properties = InterlightJwtProperties(true, "current", pem("PRIVATE KEY", current.private.encoded))
    private val issuer = InterlightJwtIssuer(properties)

    @ParameterizedTest
    @EnumSource(UserRole::class)
    @DisplayName("고정 시각의 RS256 토큰은 전용 claim과 현재 역할에 맞는 scope 및 고유 jti를 가진다")
    fun signedClaimsFollowContract(role: UserRole) {
        val userId = UUID.randomUUID()
        val token = issuer.issue(userId, role, now)
        val signed =
            Jwts.parser().verifyWith(current.public as RSAPublicKey)
                .clock { Date.from(now) }.build().parseSignedClaims(token)
        assertThat(signed.header.algorithm).isEqualTo("RS256")
        assertThat(signed.header.keyId).isEqualTo("current")
        val claims = signed.payload
        assertThat(claims.issuer).isEqualTo("urn:techtaurant:interlight")
        assertThat(claims.audience).containsExactly("interlight-api")
        assertThat(claims.subject).isEqualTo(userId.toString())
        assertThat(claims.issuedAt.toInstant()).isEqualTo(now)
        assertThat(claims.expiration.toInstant()).isEqualTo(now.plusSeconds(300))
        assertThat(claims["scope"]).isEqualTo(if (role == UserRole.ADMIN) "interlight:use interlight:admin" else "interlight:use")
        assertThat(UUID.fromString(claims.id)).isNotNull()
        assertThat(claims.keys).containsExactlyInAnyOrder("iss", "aud", "sub", "iat", "exp", "jti", "scope")
        assertThat(issuer.issue(userId, role, now)).isNotEqualTo(token)
    }

    @Test
    @DisplayName("키 교체 JWKS에는 현재 키와 이전 공개키만 있고 개인 성분이 없다")
    fun rotationContainsOnlyPublicKeys() {
        val rotated =
            InterlightJwtIssuer(
                InterlightJwtProperties(
                    true,
                    "current",
                    properties.privateKeyPem,
                    listOf(InterlightJwtProperties.PreviousPublicKey("old", pem("PUBLIC KEY", old.public.encoded))),
                ),
            )
        val keys = rotated.jwks().keys
        assertThat(keys.map { it.kid }).containsExactly("current", "old")
        val serialized = com.fasterxml.jackson.databind.ObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(rotated.jwks())
        assertThat(serialized.path("keys").flatMap { it.fieldNames().asSequence().toList() })
            .doesNotContain("d", "p", "q", "dp", "dq", "qi", "oth", "k")
        assertThat(keys.map { it.kty }).containsOnly("RSA")
        assertThat(keys.map { it.use }).containsOnly("sig")
        assertThat(keys.map { it.alg }).containsOnly("RS256")
        val publishedOld =
            io.jsonwebtoken.security.Jwks.parser().build().parse(
                com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(keys.last()),
            )
        assertThat(publishedOld.toKey()).isEqualTo(old.public)
    }

    @Test
    @DisplayName("비활성 기본 설정과 누락된 키 및 잘못된 PEM은 생성 시 시작을 막지 않고 사용 시 503이다")
    fun unavailableKeysFailClosedLazily() {
        assertUnavailable(InterlightJwtProperties())
        assertUnavailable(InterlightJwtProperties(true, "current", ""))
        assertUnavailable(InterlightJwtProperties(true, "current", "not a private key"))
        assertUnavailable(InterlightJwtProperties(false, "current", properties.privateKeyPem))
    }

    @Test
    @DisplayName("2048비트 미만 RSA와 중복 kid 및 비RSA 키는 거절한다")
    fun invalidKeyPolicyFailsClosed() {
        val weak = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        assertUnavailable(InterlightJwtProperties(true, "weak", pem("PRIVATE KEY", weak.private.encoded)))
        assertUnavailable(
            InterlightJwtProperties(
                true,
                "current",
                properties.privateKeyPem,
                listOf(InterlightJwtProperties.PreviousPublicKey("current", pem("PUBLIC KEY", old.public.encoded))),
            ),
        )
        val ec = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        assertUnavailable(InterlightJwtProperties(true, "ec", pem("PRIVATE KEY", ec.private.encoded)))
    }

    private fun assertUnavailable(properties: InterlightJwtProperties) {
        val unavailable = InterlightJwtIssuer(properties)
        assertThatThrownBy { unavailable.issue(UUID.randomUUID(), UserRole.USER, now) }
            .isInstanceOfSatisfying(ApiException::class.java) { assertThat(it.status.getHttpStatusCode()).isEqualTo(503) }
        assertThatThrownBy { unavailable.jwks() }.isInstanceOf(ApiException::class.java)
    }

    companion object {
        private val now = Instant.parse("2026-01-01T00:00:00Z")
        private val current = Jwts.SIG.RS256.keyPair().build()
        private val old = Jwts.SIG.RS256.keyPair().build()

        private fun pem(
            label: String,
            bytes: ByteArray,
        ): String = "-----BEGIN $label-----\n${Base64.getEncoder().encodeToString(bytes)}\n-----END $label-----"
    }
}
