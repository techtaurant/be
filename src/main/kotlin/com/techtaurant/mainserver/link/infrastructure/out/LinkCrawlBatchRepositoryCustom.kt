package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import java.util.UUID

interface LinkCrawlBatchRepositoryCustom {
    fun save(batch: LinkCrawlBatch): LinkCrawlBatch

    fun saveAndFlush(batch: LinkCrawlBatch): LinkCrawlBatch

    fun findAll(): List<LinkCrawlBatch>

    fun findById(id: UUID): java.util.Optional<LinkCrawlBatch>

    fun existsById(id: UUID): Boolean

    fun findAllByCompanyUserId(companyUserId: UUID): List<LinkCrawlBatch>

    fun findAllByActiveTrue(): List<LinkCrawlBatch>
}
