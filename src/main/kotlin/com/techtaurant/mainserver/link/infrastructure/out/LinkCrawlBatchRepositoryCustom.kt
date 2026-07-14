package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import java.util.Optional
import java.util.UUID

interface LinkCrawlBatchRepositoryCustom {
    fun findById(id: UUID): Optional<LinkCrawlBatch>

    fun existsById(id: UUID): Boolean

    fun findAllByCompanyUserId(companyUserId: UUID): List<LinkCrawlBatch>

    fun findAllByActiveTrue(): List<LinkCrawlBatch>
}
