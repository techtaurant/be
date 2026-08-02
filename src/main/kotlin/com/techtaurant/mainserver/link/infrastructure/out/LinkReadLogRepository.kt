package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkReadLog
import org.springframework.data.repository.Repository
import java.util.UUID

interface LinkReadLogRepository : Repository<LinkReadLog, UUID>, LinkReadLogRepositoryCustom {
    override fun save(log: LinkReadLog): LinkReadLog

    override fun delete(log: LinkReadLog)

    override fun findByUserIdAndLinkId(
        userId: UUID,
        linkId: UUID,
    ): LinkReadLog?

    override fun existsByUserIdAndLinkId(
        userId: UUID,
        linkId: UUID,
    ): Boolean

    override fun findByUserIdAndLinkIdIn(
        userId: UUID,
        linkIds: List<UUID>,
    ): List<LinkReadLog>
}
