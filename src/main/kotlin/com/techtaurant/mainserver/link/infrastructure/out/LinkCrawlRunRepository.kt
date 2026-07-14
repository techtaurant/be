package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkCrawlRun
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface LinkCrawlRunRepository : JpaRepository<LinkCrawlRun, UUID>, LinkCrawlRunRepositoryCustom {
    override fun findById(id: UUID): Optional<LinkCrawlRun>

    override fun existsById(id: UUID): Boolean
}
