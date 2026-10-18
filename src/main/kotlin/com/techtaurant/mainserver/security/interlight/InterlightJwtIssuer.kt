package com.techtaurant.mainserver.security.interlight

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.user.enums.UserRole
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Jwks
import io.jsonwebtoken.security.RsaPublicJwk
import org.springframework.stereotype.Component
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

@Component
class InterlightJwtIssuer(private val properties: InterlightJwtProperties) {
    private val material: Material? by lazy {
        if (!properties.enabled) {
            null
        } else {
            try {
                loadMaterial()
            } catch (_: Exception) {
                // 키 설정 오류가 기존 로그인을 중단하거나 예외에 담긴 비밀 키를 로그로 내보내지 않게 사용 시 503으로 처리한다.
                null
            }
        }
    }

    fun issue(
        userId: UUID,
        role: UserRole,
        now: Instant = Instant.now(),
    ): String {
        val keys = requireMaterial()
        val issuedAt = Instant.ofEpochSecond(now.epochSecond)
        return Jwts.builder().header().keyId(properties.kid).and()
            .issuer(ISSUER).audience().add(AUDIENCE).and().subject(userId.toString())
            .claim("scope", if (role == UserRole.ADMIN) "interlight:use interlight:admin" else "interlight:use")
            .id(UUID.randomUUID().toString()).issuedAt(Date.from(issuedAt))
            .expiration(Date.from(issuedAt.plusSeconds(TTL_SECONDS)))
            .signWith(keys.privateKey, Jwts.SIG.RS256).compact()
    }

    fun jwks(): InterlightJwksResponse = InterlightJwksResponse(requireMaterial().publicKeys)

    private fun requireMaterial(): Material = material ?: throw ApiException(InterlightJwtStatus.KEYS_UNAVAILABLE)

    private fun loadMaterial(): Material {
        val ids = listOf(properties.kid) + properties.previousPublicKeys.map { it.kid }
        require(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size)
        val factory = KeyFactory.getInstance("RSA")
        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(decodePem(properties.privateKeyPem, "PRIVATE KEY"))) as RSAPrivateKey
        require(privateKey.modulus.bitLength() >= MIN_RSA_BITS)
        val current = Jwks.builder().key(privateKey).id(properties.kid).algorithm("RS256").publicKeyUse("sig").build().toPublicJwk()
        val previous =
            properties.previousPublicKeys.map { configured ->
                val publicKey = factory.generatePublic(X509EncodedKeySpec(decodePem(configured.publicKeyPem, "PUBLIC KEY"))) as RSAPublicKey
                require(publicKey.modulus.bitLength() >= MIN_RSA_BITS)
                Jwks.builder().key(publicKey).id(configured.kid).algorithm("RS256").publicKeyUse("sig").build()
            }
        return Material(privateKey, (listOf(current) + previous).map(::publicResponse))
    }

    private fun publicResponse(jwk: RsaPublicJwk): InterlightPublicJwk =
        InterlightPublicJwk(jwk.type, jwk["use"] as String, jwk.algorithm, jwk.id, jwk["n"] as String, jwk["e"] as String)

    private fun decodePem(
        pem: String,
        label: String,
    ): ByteArray {
        val begin = "-----BEGIN $label-----"
        val end = "-----END $label-----"
        val trimmed = pem.trim()
        require(trimmed.startsWith(begin) && trimmed.endsWith(end))
        return Base64.getDecoder().decode(trimmed.removePrefix(begin).removeSuffix(end).replace(Regex("\\s+"), ""))
    }

    private class Material(val privateKey: RSAPrivateKey, val publicKeys: List<InterlightPublicJwk>)

    companion object {
        const val ISSUER = "urn:techtaurant:interlight"
        const val AUDIENCE = "interlight-api"
        const val TTL_SECONDS = 300L
        private const val MIN_RSA_BITS = 2048
    }
}
