package com.techtaurant.mainserver.security.infrastructure.`in`

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.security.infrastructure.out.RefreshTokenStore
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtProperties
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
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

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

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

    @Test
    @DisplayName("기기별 로그아웃은 요청에 실려 온 refreshToken의 세션만 폐기하고 다른 기기 세션을 남긴다")
    fun currentDeviceLogoutRevokesOnlyTheRequestingSession() {
        val userId = createUser()
        val thisDeviceRefreshToken = jwtTokenProvider.createRefreshToken(userId)
        refreshTokenStore.save(userId, thisDeviceRefreshToken)
        refreshTokenStore.save(userId, OTHER_DEVICE_REFRESH_TOKEN)

        given()
            .cookie(JwtConstants.REFRESH_TOKEN_COOKIE, thisDeviceRefreshToken)
            .`when`()
            .post("/open-api/auth/logout")
            .then()
            .statusCode(HttpStatus.OK.value())

        assertThat(refreshTokenStore.exists(userId, thisDeviceRefreshToken)).isFalse()
        assertThat(refreshTokenStore.exists(userId, OTHER_DEVICE_REFRESH_TOKEN)).isTrue()
    }

    @Test
    @DisplayName("옛 경로는 인증 쿠키만 삭제하고 서버에 저장된 세션은 모두 남긴다")
    fun deprecatedLogoutLeavesEverySessionOfTheUser() {
        val userId = createUser()
        val thisDeviceRefreshToken = jwtTokenProvider.createRefreshToken(userId)
        refreshTokenStore.save(userId, thisDeviceRefreshToken)
        refreshTokenStore.save(userId, OTHER_DEVICE_REFRESH_TOKEN)

        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, jwtTokenProvider.createAccessToken(userId, UserRole.USER))
            .`when`()
            .post("/api/auth/logout")
            .then()
            .statusCode(HttpStatus.OK.value())

        assertThat(refreshTokenStore.exists(userId, thisDeviceRefreshToken)).isTrue()
        assertThat(refreshTokenStore.exists(userId, OTHER_DEVICE_REFRESH_TOKEN)).isTrue()
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

    private companion object {
        // createRefreshToken은 jti가 없어 같은 밀리초의 두 호출이 같은 토큰을 내므로,
        // 다른 기기의 행은 구분되는 값으로 심는다. 저장소는 해시만 대조하므로 JWT일 필요가 없다.
        const val OTHER_DEVICE_REFRESH_TOKEN = "other-device-refresh-token"
    }
}
