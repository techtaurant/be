package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkCrawlFailedJob
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.util.UUID

interface LinkCrawlFailedJobRepositoryCustom {
    fun findById(id: UUID): java.util.Optional<LinkCrawlFailedJob>

    fun findAllByLastRunIdAndResolvedAtIsNullOrderByCreatedAtAsc(runId: UUID): List<LinkCrawlFailedJob>

    fun findAllByLastRunIdAndResolvedAtIsNullOrderByCreatedAtAsc(
        runId: UUID,
        pageable: Pageable,
    ): List<LinkCrawlFailedJob>

    fun countByLastRunIdAndResolvedAtIsNull(runId: UUID): Long

    fun findRetryableAutomaticJobs(
        maxFailureCount: Int,
        retryableBefore: Instant,
        pageable: Pageable,
    ): List<LinkCrawlFailedJob>

    fun findAllByBatchIdFilteredByResolution(
        batchId: UUID,
        resolved: Boolean?,
    ): List<LinkCrawlFailedJob>

    fun findByBatchIdAndArticleUrl(
        batchId: UUID,
        articleUrl: String,
    ): LinkCrawlFailedJob?

    fun findRunIdsWithUnresolvedJobs(runIds: Collection<UUID>): Set<UUID>

    fun recordFailure(
        batchId: UUID,
        articleUrl: String,
        lastRunId: UUID,
        errorStatusCode: Int,
        errorMessage: String,
        failedAt: Instant,
    )

    fun markResolvedIfUnresolved(
        batchId: UUID,
        articleUrl: String,
        resolvedAt: Instant,
    ): Boolean

    fun recordRetryFailureIfUnresolved(
        failedJobId: UUID,
        errorStatusCode: Int,
        errorMessage: String,
        failedAt: Instant,
    )
}
