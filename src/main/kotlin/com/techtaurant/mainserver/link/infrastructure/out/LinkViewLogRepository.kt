package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkViewLog
import org.springframework.data.repository.Repository
import java.util.UUID

interface LinkViewLogRepository : Repository<LinkViewLog, UUID>, LinkViewLogRepositoryCustom {
    override fun save(log: LinkViewLog): LinkViewLog

    override fun findAll(): List<LinkViewLog>

    override fun findDistinctLinkIdsByUserIdAndLinkIdIn(
        userId: UUID,
        linkIds: List<UUID>,
    ): List<UUID>
}
