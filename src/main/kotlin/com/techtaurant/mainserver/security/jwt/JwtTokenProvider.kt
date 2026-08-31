package com.techtaurant.mainserver.security.jwt

import com.techtaurant.mainserver.user.enums.UserRole
import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.MalformedJwtException
import io.jsonwebtoken.UnsupportedJwtException
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Instant
import java.util.Date
import java.util.HexFormat
import java.util.UUID
import javax.crypto.SecretKey

@Component
class JwtTokenProvider(
    private val jwtProperties: JwtProperties,
) {
    private val secretKey: SecretKey by lazy {
        Keys.hmacShaKeyFor(jwtProperties.secret.toByteArray())
    }

    fun createAccessToken(
        userId: UUID,
        role: UserRole,
    ): String {
        val now = Instant.now()
        val expiryAt = now.plusMillis(jwtProperties.accessTokenExpireMs)

        return Jwts.builder()
            .subject(userId.toString())
            .claim(JwtConstants.TOKEN_TYPE_CLAIM, JwtConstants.ACCESS_TOKEN_TYPE)
            .claim(JwtConstants.ROLE_CLAIM, role.key)
            .claim(JwtConstants.PERMANENT_CLAIM, JwtConstants.EXPIRING_ACCESS_TOKEN_IS_PERMANENT)
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiryAt))
            .signWith(secretKey)
            .compact()
    }

    fun createPermanentAccessToken(
        userId: UUID,
        role: UserRole,
    ): String {
        val now = Instant.now()

        return Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(userId.toString())
            .claim(JwtConstants.TOKEN_TYPE_CLAIM, JwtConstants.ACCESS_TOKEN_TYPE)
            .claim(JwtConstants.ROLE_CLAIM, role.key)
            .claim(JwtConstants.PERMANENT_CLAIM, JwtConstants.PERMANENT_ACCESS_TOKEN_IS_PERMANENT)
            .issuedAt(Date.from(now))
            .signWith(secretKey)
            .compact()
    }

    fun createRefreshToken(userId: UUID): String {
        return createToken(userId, jwtProperties.refreshTokenExpireMs)
    }

    private fun createToken(
        userId: UUID,
        expiration: Long,
    ): String {
        val now = Instant.now()
        val expiryAt = now.plusMillis(expiration)

        return Jwts.builder()
            .subject(userId.toString())
            .claim(JwtConstants.TOKEN_TYPE_CLAIM, JwtConstants.REFRESH_TOKEN_TYPE)
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiryAt))
            .signWith(secretKey)
            .compact()
    }

    /**
     * RefreshToken을 검증하고 userId를 추출합니다.
     * 한 번의 파싱으로 검증과 추출을 동시에 수행하여 성능을 최적화합니다.
     *
     * @param token RefreshToken
     * @return 토큰에서 추출한 userId
     * @throws ExpiredJwtException 토큰이 만료된 경우
     * @throws UnsupportedJwtException 지원하지 않는 토큰 형식인 경우
     * @throws MalformedJwtException 잘못된 형식의 토큰인 경우
     * @throws SecurityException 서명 검증에 실패한 경우
     * @throws IllegalArgumentException RefreshToken이 아닌 토큰인 경우
     */
    fun validateAndGetRefreshTokenUserId(token: String): UUID {
        val claims = getClaims(token)
        requireTokenType(claims, JwtConstants.REFRESH_TOKEN_TYPE)

        return UUID.fromString(claims.subject)
    }

    /**
     * AccessToken을 검증하고 Claims를 추출합니다.
     *
     * userId와 role, 영구 토큰 여부를 포함한 Claims 객체를 반환합니다.
     *
     * @param token AccessToken
     * @return JWT에서 추출한 Claims (userId + role + 영구 토큰 여부)
     * @throws ExpiredJwtException 토큰이 만료된 경우
     * @throws UnsupportedJwtException 지원하지 않는 토큰 형식인 경우
     * @throws MalformedJwtException 잘못된 형식의 토큰인 경우
     * @throws SecurityException 서명 검증에 실패한 경우
     * @throws IllegalArgumentException AccessToken이 아닌 토큰인 경우
     */
    fun validateAndGetClaims(token: String): JwtClaims {
        val claims = getClaims(token)
        requireTokenType(claims, JwtConstants.ACCESS_TOKEN_TYPE)

        return JwtClaims(
            userId = UUID.fromString(claims.subject),
            role = claims[JwtConstants.ROLE_CLAIM] as String,
            isPermanent =
                claims[JwtConstants.PERMANENT_CLAIM] as? Boolean
                    ?: JwtConstants.EXPIRING_ACCESS_TOKEN_IS_PERMANENT,
        )
    }

    /**
     * 종류 표시가 없는 예전 토큰과 다른 용도로 발급된 토큰을 같은 자리에서 걸러냅니다.
     * IllegalArgumentException은 JwtExceptionMapper가 INVALID_TOKEN으로 옮깁니다.
     */
    private fun requireTokenType(
        claims: Claims,
        expectedTokenType: String,
    ) {
        val tokenType = claims[JwtConstants.TOKEN_TYPE_CLAIM] as? String

        require(tokenType == expectedTokenType) {
            "허용되지 않는 토큰 종류입니다: expected=$expectedTokenType, actual=$tokenType"
        }
    }

    fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance(JwtConstants.TOKEN_HASH_ALGORITHM).digest(token.toByteArray())
        return HexFormat.of().formatHex(digest)
    }

    private fun getClaims(token: String): Claims {
        return Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)
            .payload
    }
}
