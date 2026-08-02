package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkCrawlRun
import org.springframework.data.repository.Repository
import java.util.Optional
import java.util.UUID

interface LinkCrawlRunRepository : Repository<LinkCrawlRun, UUID>, LinkCrawlRunRepositoryCustom {
    override fun save(run: LinkCrawlRun): LinkCrawlRun

    override fun findById(id: UUID): Optional<LinkCrawlRun>

    override fun existsById(id: UUID): Boolean
}
