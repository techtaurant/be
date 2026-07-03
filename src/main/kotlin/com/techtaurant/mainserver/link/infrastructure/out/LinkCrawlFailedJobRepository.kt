package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkCrawlFailedJob
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface LinkCrawlFailedJobRepository : JpaRepository<LinkCrawlFailedJob, UUID> {
    fun findAllByRunIdAndResolvedFalseOrderByCreatedAtAsc(runId: UUID): List<LinkCrawlFailedJob>

    @Query(
        """
        SELECT j
        FROM LinkCrawlFailedJob j
        JOIN j.run r
        JOIN r.batch b
        WHERE j.resolved = false
          AND b.active = true
          AND j.failureCount < :maxFailureCount
          AND j.lastFailedAt <= :retryableBefore
        ORDER BY j.createdAt ASC
        """,
    )
    fun findRetryableAutomaticJobs(
        @Param("maxFailureCount") maxFailureCount: Int,
        @Param("retryableBefore") retryableBefore: Instant,
        pageable: Pageable,
    ): List<LinkCrawlFailedJob>

    fun existsByRunIdAndResolvedFalse(runId: UUID): Boolean

    fun findByRunIdAndArticleUrl(
        runId: UUID,
        articleUrl: String,
    ): LinkCrawlFailedJob?

    @Query(
        "SELECT DISTINCT j.run.id FROM LinkCrawlFailedJob j WHERE j.run.id IN :runIds AND j.resolved = false",
    )
    fun findRunIdsWithUnresolvedJobs(runIds: Collection<UUID>): Set<UUID>
}
