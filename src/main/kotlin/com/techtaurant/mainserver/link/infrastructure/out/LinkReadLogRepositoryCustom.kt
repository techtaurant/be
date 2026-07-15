package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkReadLog
import java.util.UUID

interface LinkReadLogRepositoryCustom {
    fun save(log: LinkReadLog): LinkReadLog

    fun delete(log: LinkReadLog)

    fun findByUserIdAndLinkId(
        userId: UUID,
        linkId: UUID,
    ): LinkReadLog?

    fun existsByUserIdAndLinkId(
        userId: UUID,
        linkId: UUID,
    ): Boolean

    fun findByUserIdAndLinkIdIn(
        userId: UUID,
        linkIds: List<UUID>,
    ): List<LinkReadLog>
}
