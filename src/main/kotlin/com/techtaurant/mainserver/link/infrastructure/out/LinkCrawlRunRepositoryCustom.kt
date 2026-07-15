package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkCrawlRun
import java.util.UUID

interface LinkCrawlRunRepositoryCustom {
    fun save(run: LinkCrawlRun): LinkCrawlRun

    fun findById(id: UUID): java.util.Optional<LinkCrawlRun>

    fun existsById(id: UUID): Boolean

    fun findAllByBatchIdOrderByStartedAtDesc(batchId: UUID): List<LinkCrawlRun>
}
