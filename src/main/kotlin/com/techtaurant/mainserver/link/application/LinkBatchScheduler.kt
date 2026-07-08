package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import com.techtaurant.mainserver.link.enums.LinkCrawlRunTriggerType
import com.techtaurant.mainserver.link.infrastructure.out.LinkCrawlBatchRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

@Component
class LinkBatchScheduler(
    private val linkCrawlBatchRepository: LinkCrawlBatchRepository,
    private val linkBatchRunService: LinkBatchRunService,
) {
    @Scheduled(fixedDelay = 60_000L)
    fun runDueBatches() {
        runDueBatches(Instant.now())
    }

    /**
     * 미해소 실패 잡만 주기적으로 자동 재시도한다. 외부 사이트 부담을 줄이기 위해 보수적인 주기(30분)를 사용한다.
     */
    @Scheduled(fixedDelay = 1_800_000L)
    fun retryUnresolvedFailedJobs() {
        linkBatchRunService.retryAllUnresolvedFailedJobs()
    }

    internal fun runDueBatches(now: Instant) {
        linkCrawlBatchRepository.findAllByActiveTrue()
            .filter { shouldRun(it, now) }
            .forEach { batch ->
                batch.id?.let { linkBatchRunService.run(it, LinkCrawlRunTriggerType.SCHEDULED) }
            }
    }

    internal fun shouldRun(
        batch: LinkCrawlBatch,
        now: Instant = Instant.now(),
    ): Boolean {
        val cronExpression = runCatching { CronExpression.parse(batch.cronExpression) }.getOrNull() ?: return false
        val nowUtc = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
        val referenceTime =
            batch.lastTriggeredAt?.let { ZonedDateTime.ofInstant(it, ZoneOffset.UTC) }
                ?: nowUtc.minusMinutes(1)

        val nextExecution = cronExpression.next(referenceTime) ?: return false
        return !nextExecution.isAfter(nowUtc)
    }
}
