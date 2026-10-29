package com.techtaurant.mainserver.common.lock

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.common.exception.ApiException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import javax.sql.DataSource

class ShedLockIntegrationTest : IntegrationTest() {
    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `independent executors exclude the same key and release after completion without a transaction`() {
        val first = ShedLock(dataSource)
        val second = ShedLock(dataSource)
        var blockedActionExecuted = false

        val result: String? =
            first.withLock("shared-work", Duration.ofMinutes(10), Duration.ZERO) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                assertThatThrownBy {
                    second.withLock("shared-work", Duration.ofMinutes(10), Duration.ZERO) {
                        blockedActionExecuted = true
                    }
                }.isInstanceOfSatisfying(ApiException::class.java) {
                    assertThat(it.status).isEqualTo(LockStatus.LOCK_ACQUISITION_FAILED)
                }
                null
            }

        assertThat(result).isNull()
        assertThat(blockedActionExecuted).isFalse()
        assertThat(second.withLock("shared-work", Duration.ofMinutes(10), Duration.ZERO) { "completed" })
            .isEqualTo("completed")
    }

    @Test
    fun `action failure is propagated and another executor can acquire the released lock`() {
        val first = ShedLock(dataSource)
        val second = ShedLock(dataSource)
        val failure = IllegalStateException("publication failed")

        assertThatThrownBy {
            first.withLock("failed-work", Duration.ofMinutes(10), Duration.ZERO) { throw failure }
        }.isSameAs(failure)
        assertThat(second.withLock("failed-work", Duration.ofMinutes(10), Duration.ZERO) { "retried" })
            .isEqualTo("retried")
    }

    @Test
    fun `minimum duration prevents an immediate rerun and an expired lease can be acquired`() {
        val first = ShedLock(dataSource)
        val second = ShedLock(dataSource)
        first.withLock("retained-work", Duration.ofMinutes(10), Duration.ofMinutes(1)) { Unit }

        assertThatThrownBy {
            second.withLock("retained-work", Duration.ofMinutes(10), Duration.ZERO) { error("must not run") }
        }.isInstanceOfSatisfying(ApiException::class.java) {
            assertThat(it.status).isEqualTo(LockStatus.LOCK_ACQUISITION_FAILED)
        }

        jdbc.update(
            "UPDATE shedlock SET lock_until = (now() AT TIME ZONE 'UTC') - interval '1 second' WHERE name = ?",
            "retained-work",
        )
        assertThat(second.withLock("retained-work", Duration.ofMinutes(10), Duration.ZERO) { "recovered" })
            .isEqualTo("recovered")
    }
}
