package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkCrawlFailedJob
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.Repository
import java.util.Optional
import java.util.UUID

interface LinkCrawlFailedJobRepository : Repository<LinkCrawlFailedJob, UUID>, LinkCrawlFailedJobRepositoryCustom {
    override fun findById(id: UUID): Optional<LinkCrawlFailedJob>

    override fun findAllByLastRunIdAndResolvedAtIsNullOrderByCreatedAtAsc(runId: UUID): List<LinkCrawlFailedJob>

    override fun findAllByLastRunIdAndResolvedAtIsNullOrderByCreatedAtAsc(
        runId: UUID,
        pageable: Pageable,
    ): List<LinkCrawlFailedJob>

    override fun countByLastRunIdAndResolvedAtIsNull(runId: UUID): Long

    override fun findAllByBatchIdFilteredByResolution(
        batchId: UUID,
        resolved: Boolean?,
    ): List<LinkCrawlFailedJob>
}
