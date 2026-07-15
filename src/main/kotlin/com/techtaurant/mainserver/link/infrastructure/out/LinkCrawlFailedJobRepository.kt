package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkCrawlFailedJob
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.Repository
import java.util.Optional
import java.util.UUID

interface LinkCrawlFailedJobRepository : Repository<LinkCrawlFailedJob, UUID>, LinkCrawlFailedJobRepositoryCustom {
    override fun save(job: LinkCrawlFailedJob): LinkCrawlFailedJob

    override fun findById(id: UUID): Optional<LinkCrawlFailedJob>

    override fun findAllByRunIdAndResolvedAtIsNullOrderByCreatedAtAsc(runId: UUID): List<LinkCrawlFailedJob>

    override fun findAllByRunIdAndResolvedAtIsNullOrderByCreatedAtAsc(
        runId: UUID,
        pageable: Pageable,
    ): List<LinkCrawlFailedJob>

    override fun countByRunIdAndResolvedAtIsNull(runId: UUID): Long
}
