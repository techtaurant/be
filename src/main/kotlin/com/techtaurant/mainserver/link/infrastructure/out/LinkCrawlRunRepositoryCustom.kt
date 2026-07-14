package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkCrawlRun
import java.util.Optional
import java.util.UUID

interface LinkCrawlRunRepositoryCustom {
    fun findById(id: UUID): Optional<LinkCrawlRun>

    fun existsById(id: UUID): Boolean

    fun findAllByBatchIdOrderByStartedAtDesc(batchId: UUID): List<LinkCrawlRun>
}
