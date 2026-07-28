package com.techtaurant.mainserver.security.infrastructure.out

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.security.config.RefreshTokenWhitelistPolicy
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.security.service.RefreshTokenWhitelistService
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DisplayName("DB Refresh Token Whitelist")
class DbRefreshTokenWhitelistServiceIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var refreshTokenRepository: RefreshTokenRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var whitelistServices: List<RefreshTokenWhitelistService>

    @ParameterizedTest(name = "상한 {0}개를 넘기지 않고 최신 토큰을 유지한다")
    @ValueSource(ints = [1, 3, 5])
    fun register_respectsConfiguredLimit(limit: Int) {
        val userId = createUser()
        val service = createService(limit)
        val tokens = (1..(limit + 1)).map { "token-$it-${UUID.randomUUID()}" }

        tokens.forEach { token -> service.register(userId, token) }

        assertThat(findTokenHashes(userId))
            .hasSize(limit)
            .containsExactlyInAnyOrderElementsOf(tokens.takeLast(limit))
    }

    @ParameterizedTest(name = "동시 등록에서도 상한 {0}개를 넘지 않는다")
    @ValueSource(ints = [1, 3, 5])
    fun register_concurrently_neverExceedsConfiguredLimit(limit: Int) {
        val userId = createUser()
        val service = createService(limit)
        val requestCount = limit + 2
        val ready = CountDownLatch(requestCount)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(requestCount)

        try {
            val futures =
                (1..requestCount).map { index ->
                    executor.submit {
                        ready.countDown()
                        start.await()
                        service.register(userId, "token-$index-${UUID.randomUUID()}")
                    }
                }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }

            assertThat(findTokenHashes(userId)).hasSize(limit)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    @DisplayName("한 사용자 행의 잠금은 다른 사용자의 등록을 막지 않는다")
    fun userLock_isIsolatedByUser() {
        val lockedUserId = createUser()
        val otherUserId = createUser()
        val service = createService(3)
        val lockAcquired = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val lockFuture =
                executor.submit {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        check(refreshTokenRepository.lockUser(lockedUserId))
                        lockAcquired.countDown()
                        check(releaseLock.await(5, TimeUnit.SECONDS))
                    }
                }
            assertThat(lockAcquired.await(5, TimeUnit.SECONDS)).isTrue()

            val otherUserFuture =
                executor.submit {
                    service.register(otherUserId, "other-user-${UUID.randomUUID()}")
                }

            otherUserFuture.get(5, TimeUnit.SECONDS)
            assertThat(findTokenHashes(otherUserId)).hasSize(1)
            releaseLock.countDown()
            lockFuture.get(5, TimeUnit.SECONDS)
        } finally {
            releaseLock.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    @DisplayName("동일한 기존 토큰의 동시 회전은 정확히 한 요청만 성공한다")
    fun rotate_sameTokenConcurrently_allowsOneSuccess() {
        val userId = createUser()
        val service = createService(3)
        val originalToken = "original-${UUID.randomUUID()}"
        service.register(userId, originalToken)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val futures =
                listOf("replacement-a", "replacement-b").map { replacement ->
                    executor.submit<Boolean> {
                        ready.countDown()
                        start.await()
                        service.rotate(userId, originalToken, "$replacement-${UUID.randomUUID()}")
                    }
                }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()

            assertThat(futures.map { it.get(10, TimeUnit.SECONDS) }).containsExactlyInAnyOrder(true, false)
            assertThat(findTokenHashes(userId)).hasSize(1).doesNotContain(originalToken)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    @DisplayName("상한을 낮추면 첫 회전에서 오래된 초과 세션을 제거한다")
    fun rotate_afterLimitDecrease_removesOldestExcessSessions() {
        val userId = createUser()
        val initialService = createService(5)
        val tokens = (1..5).map { "token-$it-${UUID.randomUUID()}" }
        tokens.forEach { token -> initialService.register(userId, token) }
        tokens.forEachIndexed { index, token ->
            jdbcTemplate.update(
                "UPDATE refresh_tokens SET created_at_utc = ? WHERE token_hash = ?",
                OffsetDateTime.of(2026, 1, index + 1, 0, 0, 0, 0, ZoneOffset.UTC),
                token,
            )
        }

        val reducedLimitService = createService(3)
        val rotated = reducedLimitService.rotate(userId, tokens.last(), "rotated-${UUID.randomUUID()}")

        assertThat(rotated).isTrue()
        assertThat(findTokenHashes(userId)).hasSize(3).doesNotContain(tokens[0], tokens[1], tokens.last())
    }

    @Test
    @DisplayName("로그아웃 폐기는 사용자의 모든 세션을 제거한다")
    fun revokeAll_removesEverySession() {
        val userId = createUser()
        val service = createService(5)
        repeat(5) { service.register(userId, "token-$it-${UUID.randomUUID()}") }

        val deletedCount = service.revokeAll(userId)

        assertThat(deletedCount).isEqualTo(5)
        assertThat(findTokenHashes(userId)).isEmpty()
    }

    @Test
    @DisplayName("등록 중 예외가 발생하면 오래된 토큰 삭제도 rollback되고 다음 등록이 가능하다")
    fun register_whenInsertFails_rollsBackAndRecovers() {
        val userId = createUser()
        val anotherUserId = createUser()
        val service = createService(1)
        val originalHash = "original-${UUID.randomUUID()}"
        val duplicateHash = "duplicate-${UUID.randomUUID()}"
        service.register(userId, originalHash)
        service.register(anotherUserId, duplicateHash)

        assertThatThrownBy { service.register(userId, duplicateHash) }
        assertThat(findTokenHashes(userId)).containsExactly(originalHash)

        val replacementHash = "replacement-${UUID.randomUUID()}"
        service.register(userId, replacementHash)
        assertThat(findTokenHashes(userId)).containsExactly(replacementHash)
    }

    @Test
    @DisplayName("새 whitelist 서비스 인스턴스도 기존 DB 토큰을 회전할 수 있다")
    fun persistedToken_survivesServiceRecreation() {
        val userId = createUser()
        val originalHash = "original-${UUID.randomUUID()}"
        createService(3).register(userId, originalHash)

        val rotated = createService(3).rotate(userId, originalHash, "replacement-${UUID.randomUUID()}")

        assertThat(rotated).isTrue()
    }

    @Test
    @DisplayName("사용자를 삭제하면 Refresh Token도 cascade 삭제된다")
    fun deletingUser_cascadesRefreshTokens() {
        val userId = createUser()
        createService(3).register(userId, "token-${UUID.randomUUID()}")

        userRepository.delete(userRepository.findById(userId).orElseThrow())

        assertThat(findTokenHashes(userId)).isEmpty()
    }

    @Test
    @DisplayName("애플리케이션은 RefreshTokenWhitelistService 구현을 하나만 제공한다")
    fun applicationContext_hasSingleWhitelistImplementation() {
        assertThat(whitelistServices)
            .singleElement()
            .isInstanceOf(DbRefreshTokenWhitelistService::class.java)
    }

    private fun createService(limit: Int): DbRefreshTokenWhitelistService =
        DbRefreshTokenWhitelistService(
            refreshTokenRepository = refreshTokenRepository,
            transactionManager = transactionManager,
            policy = RefreshTokenWhitelistPolicy(limit),
        )

    private fun createUser(): UUID {
        val user =
            userRepository.save(
                User(
                    name = "user-${UUID.randomUUID()}",
                    email = "${UUID.randomUUID()}@example.com",
                    provider = OAuthProvider.DEV_LOCAL,
                    identifier = UUID.randomUUID().toString(),
                    role = UserRole.USER,
                    profileImageUrl = "",
                ),
            )
        return requireNotNull(user.id)
    }

    private fun findTokenHashes(userId: UUID): List<String> =
        jdbcTemplate.queryForList(
            "SELECT token_hash FROM refresh_tokens WHERE user_id = ?",
            String::class.java,
            userId,
        )
}
