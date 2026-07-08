package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.link.entity.LinkCrawlFailedJob
import org.springframework.data.domain.PageRequest
import java.time.Duration
import java.time.Instant

object LinkCrawlFailedJobRetryPolicy {
    const val MAX_FAILURE_COUNT = 3
    const val BATCH_SIZE = 50
    private val BACKOFF = Duration.ofMinutes(30)

    fun retryableBefore(now: Instant): Instant = now.minus(BACKOFF)

    fun pageRequest(): PageRequest = PageRequest.of(0, BATCH_SIZE)

    fun canRetryAutomatically(
        failedJob: LinkCrawlFailedJob,
        now: Instant,
    ): Boolean {
        return failedJob.resolvedAt == null &&
            failedJob.run.batch.active &&
            failedJob.failureCount < MAX_FAILURE_COUNT &&
            !failedJob.lastFailedAt.plus(BACKOFF).isAfter(now)
    }
}
