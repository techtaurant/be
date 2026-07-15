package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkViewLog
import java.util.UUID

interface LinkViewLogRepositoryCustom {
    fun save(log: LinkViewLog): LinkViewLog

    fun findAll(): List<LinkViewLog>

    fun findDistinctLinkIdsByUserIdAndLinkIdIn(
        userId: UUID,
        linkIds: List<UUID>,
    ): List<UUID>
}
