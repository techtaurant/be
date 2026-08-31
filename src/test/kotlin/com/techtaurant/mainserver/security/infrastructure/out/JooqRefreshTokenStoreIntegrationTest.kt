package com.techtaurant.mainserver.security.infrastructure.out

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

@DisplayName("JooqRefreshTokenStore 통합 테스트")
class JooqRefreshTokenStoreIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var refreshTokenStore: RefreshTokenStore

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @Test
    @DisplayName("저장한 refresh token은 같은 값으로 대조할 때만 확인된다")
    fun storedRefreshTokenMatchesOnlyTheSameValue() {
        // Given
        val userId = createUser()

        // When
        refreshTokenStore.save(userId, REFRESH_TOKEN)

        // Then
        assertThat(refreshTokenStore.exists(userId, REFRESH_TOKEN)).isTrue()
        assertThat(refreshTokenStore.exists(userId, "another-refresh-token")).isFalse()
    }

    @Test
    @DisplayName("원문이 아니라 해시로 보관해 저장소가 유출돼도 토큰을 그대로 쓸 수 없다")
    fun refreshTokenIsStoredAsHash() {
        // Given
        val userId = createUser()

        // When
        refreshTokenStore.save(userId, REFRESH_TOKEN)

        // Then
        val storedTokenHash =
            jdbcTemplate.queryForObject(
                "SELECT token_hash FROM user_refresh_tokens WHERE user_id = ?",
                String::class.java,
                userId,
            )
        assertThat(storedTokenHash).isNotEqualTo(REFRESH_TOKEN)
    }

    @Test
    @DisplayName("한 사용자가 여러 기기에서 로그인하면 각 기기의 토큰이 함께 유효하다")
    fun refreshTokensFromMultipleDevicesCoexist() {
        // Given
        val userId = createUser()

        // When
        refreshTokenStore.save(userId, REFRESH_TOKEN)
        refreshTokenStore.save(userId, OTHER_DEVICE_REFRESH_TOKEN)

        // Then
        assertThat(refreshTokenStore.exists(userId, REFRESH_TOKEN)).isTrue()
        assertThat(refreshTokenStore.exists(userId, OTHER_DEVICE_REFRESH_TOKEN)).isTrue()
    }

    @Test
    @DisplayName("새 토큰을 저장할 때 같은 사용자의 만료된 토큰을 함께 걷어낸다")
    fun savingRefreshTokenRemovesExpiredRowsOfTheSameUser() {
        // Given
        val userId = createUser()
        refreshTokenStore.save(userId, REFRESH_TOKEN)
        expire(userId, REFRESH_TOKEN)

        // When
        refreshTokenStore.save(userId, OTHER_DEVICE_REFRESH_TOKEN)

        // Then
        assertThat(countRefreshTokensOf(userId)).isEqualTo(1)
        assertThat(refreshTokenStore.exists(userId, OTHER_DEVICE_REFRESH_TOKEN)).isTrue()
    }

    @Test
    @DisplayName("만료 시각이 지난 refresh token은 값이 같아도 확인되지 않는다")
    fun expiredRefreshTokenIsNotMatched() {
        // Given
        val userId = createUser()
        refreshTokenStore.save(userId, REFRESH_TOKEN)

        // When
        expire(userId, REFRESH_TOKEN)

        // Then
        assertThat(refreshTokenStore.exists(userId, REFRESH_TOKEN)).isFalse()
    }

    @Test
    @DisplayName("한 기기를 로그아웃해도 다른 기기의 토큰은 남는다")
    fun deletingOneRefreshTokenKeepsTheOthers() {
        // Given
        val userId = createUser()
        refreshTokenStore.save(userId, REFRESH_TOKEN)
        refreshTokenStore.save(userId, OTHER_DEVICE_REFRESH_TOKEN)

        // When
        refreshTokenStore.delete(userId, REFRESH_TOKEN)

        // Then
        assertThat(refreshTokenStore.exists(userId, REFRESH_TOKEN)).isFalse()
        assertThat(refreshTokenStore.exists(userId, OTHER_DEVICE_REFRESH_TOKEN)).isTrue()
    }

    @Test
    @DisplayName("소진은 그 토큰 한 행을 지운 첫 호출에만 성공해, 같은 토큰으로 두 번 회전할 수 없다")
    fun consumingRefreshTokenSucceedsOnlyOnce() {
        // Given
        val userId = createUser()
        refreshTokenStore.save(userId, REFRESH_TOKEN)
        refreshTokenStore.save(userId, OTHER_DEVICE_REFRESH_TOKEN)

        // When
        val firstConsume = refreshTokenStore.consume(userId, REFRESH_TOKEN)
        val secondConsume = refreshTokenStore.consume(userId, REFRESH_TOKEN)

        // Then
        assertThat(firstConsume).isTrue()
        assertThat(secondConsume).isFalse()
        assertThat(refreshTokenStore.exists(userId, OTHER_DEVICE_REFRESH_TOKEN)).isTrue()
    }

    @Test
    @DisplayName("만료 시각이 지난 refresh token은 소진되지 않는다")
    fun expiredRefreshTokenIsNotConsumed() {
        // Given
        val userId = createUser()
        refreshTokenStore.save(userId, REFRESH_TOKEN)
        expire(userId, REFRESH_TOKEN)

        // When
        val consumed = refreshTokenStore.consume(userId, REFRESH_TOKEN)

        // Then
        assertThat(consumed).isFalse()
    }

    private fun expire(
        userId: UUID,
        refreshToken: String,
    ) {
        jdbcTemplate.update(
            "UPDATE user_refresh_tokens SET expires_at = now() - interval '1 second' WHERE user_id = ? AND token_hash = ?",
            userId,
            jwtTokenProvider.hashToken(refreshToken),
        )
    }

    private fun countRefreshTokensOf(userId: UUID): Int {
        return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM user_refresh_tokens WHERE user_id = ?",
            Int::class.java,
            userId,
        ) ?: 0
    }

    private fun createUser(): UUID {
        val user =
            userRepository.save(
                User(
                    name = "재발급 사용자 ${UUID.randomUUID()}",
                    email = "refresh-${UUID.randomUUID()}@example.com",
                    provider = OAuthProvider.SYSTEM,
                    identifier = "refresh-${UUID.randomUUID()}",
                    role = UserRole.USER,
                    profileImageUrl = "",
                ),
            )

        return requireNotNull(user.id)
    }

    private companion object {
        const val REFRESH_TOKEN = "stored-refresh-token"
        const val OTHER_DEVICE_REFRESH_TOKEN = "other-device-refresh-token"
    }
}
