package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface LinkCrawlBatchRepository : JpaRepository<LinkCrawlBatch, UUID>, LinkCrawlBatchRepositoryCustom {
    override fun findById(id: UUID): Optional<LinkCrawlBatch>

    override fun existsById(id: UUID): Boolean
}
