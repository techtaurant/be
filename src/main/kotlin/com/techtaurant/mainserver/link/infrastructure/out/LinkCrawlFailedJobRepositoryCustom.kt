package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkCrawlFailedJob
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.util.UUID

interface LinkCrawlFailedJobRepositoryCustom {
    fun save(job: LinkCrawlFailedJob): LinkCrawlFailedJob

    fun findById(id: UUID): java.util.Optional<LinkCrawlFailedJob>

    fun findAllByRunIdAndResolvedAtIsNullOrderByCreatedAtAsc(runId: UUID): List<LinkCrawlFailedJob>

    fun findAllByRunIdAndResolvedAtIsNullOrderByCreatedAtAsc(
        runId: UUID,
        pageable: Pageable,
    ): List<LinkCrawlFailedJob>

    fun countByRunIdAndResolvedAtIsNull(runId: UUID): Long

    fun findRetryableAutomaticJobs(
        maxFailureCount: Int,
        retryableBefore: Instant,
        pageable: Pageable,
    ): List<LinkCrawlFailedJob>

    fun existsByRunIdAndResolvedAtIsNull(runId: UUID): Boolean

    fun findByRunIdAndArticleUrl(
        runId: UUID,
        articleUrl: String,
    ): LinkCrawlFailedJob?

    fun findRunIdsWithUnresolvedJobs(runIds: Collection<UUID>): Set<UUID>
}
