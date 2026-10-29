package com.techtaurant.mainserver.common.lock

import com.techtaurant.mainserver.common.exception.ApiException
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

/**
 * JDBC 기반의 공유 잠금으로 여러 앱 프로세스에서 같은 작업의 중복 실행을 제한합니다.
 * 잠금 만료는 DB 시간을 기준으로 판단하며, 작업 전체를 DB 트랜잭션으로 감싸지 않습니다.
 */
@Component
class ShedLock(dataSource: DataSource) {
    private val provider =
        JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(JdbcTemplate(dataSource))
                .usingDbTime()
                .build(),
        )

    /**
     * 잠금을 획득하면 작업을 실행하고, 성공·실패와 관계없이 finally에서 해제합니다.
     * 잠금을 획득하지 못하면 작업을 실행하거나 재시도하지 않습니다.
     *
     * 최대 유지 시간이 지나면 작업이 실행 중이어도 다른 프로세스가 잠금을 획득할 수 있습니다.
     * 자동 연장은 하지 않으므로 호출자는 예상 작업 시간보다 충분히 긴 제한을 지정해야 합니다.
     *
     * @param key 같은 작업을 식별하는 잠금 키
     * @param lockAtMostFor 프로세스 중단 시에도 잠금이 남지 않도록 지정하는 최대 유지 시간
     * @param lockAtLeastFor 작업이 일찍 끝나도 잠금을 유지할 최소 시간
     * @param action 잠금 획득 후 실행할 작업
     * @return 작업의 반환값
     * @throws ApiException 다른 실행이 잠금을 보유하여 획득하지 못한 경우
     */
    fun <T> withLock(
        key: String,
        lockAtMostFor: Duration,
        lockAtLeastFor: Duration,
        action: () -> T,
    ): T {
        val lock =
            provider.lock(LockConfiguration(Instant.now(), key, lockAtMostFor, lockAtLeastFor))
                .orElseThrow { ApiException(LockStatus.LOCK_ACQUISITION_FAILED) }
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }
}
