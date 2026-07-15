package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import org.springframework.data.repository.Repository
import java.util.Optional
import java.util.UUID

interface LinkCrawlBatchRepository : Repository<LinkCrawlBatch, UUID>, LinkCrawlBatchRepositoryCustom {
    override fun save(batch: LinkCrawlBatch): LinkCrawlBatch

    override fun saveAndFlush(batch: LinkCrawlBatch): LinkCrawlBatch

    override fun findAll(): List<LinkCrawlBatch>

    override fun findById(id: UUID): Optional<LinkCrawlBatch>

    override fun existsById(id: UUID): Boolean
}
