package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkCrawlRun
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LinkCrawlRunRepository : JpaRepository<LinkCrawlRun, UUID> {
    fun findAllByBatchIdOrderByStartedAtDesc(batchId: UUID): List<LinkCrawlRun>
}
