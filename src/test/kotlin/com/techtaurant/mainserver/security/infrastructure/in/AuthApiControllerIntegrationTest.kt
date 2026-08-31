package com.techtaurant.mainserver.security.infrastructure.`in`

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.security.infrastructure.out.RefreshTokenStore
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtProperties
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.time.Instant
import java.util.Date
import java.util.UUID

@DisplayName("AuthApiController 통합 테스트")
class AuthApiControllerIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var jwtProperties: JwtProperties

    @Autowired
    private lateinit var refreshTokenStore: RefreshTokenStore

    @Autowired
    private lateinit var userRepository: UserRepository

    @Test
    @DisplayName("만료된 accessToken으로 로그아웃하면 인증에 실패하고 서버 토큰을 유지한다")
    fun expiredAccessTokenCannotLogOut() {
        val userId = createUser()
        val expiredAccessToken = createExpiredAccessToken(userId)
        refreshTokenStore.save(userId, "refresh-token")

        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, expiredAccessToken)
            .`when`()
            .post("/api/auth/logout")
            .then()
            .statusCode(HttpStatus.UNAUTHORIZED.value())

        assertThat(refreshTokenStore.exists(userId, "refresh-token")).isTrue()
    }

    private fun createUser(): UUID {
        val user =
            userRepository.save(
                User(
                    name = "인증 사용자 ${UUID.randomUUID()}",
                    email = "auth-${UUID.randomUUID()}@example.com",
                    provider = OAuthProvider.SYSTEM,
                    identifier = "auth-${UUID.randomUUID()}",
                    role = UserRole.USER,
                    profileImageUrl = "",
                ),
            )

        return requireNotNull(user.id)
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
