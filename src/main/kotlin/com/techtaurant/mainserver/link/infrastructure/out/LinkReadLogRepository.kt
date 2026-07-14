package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkReadLog
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LinkReadLogRepository : JpaRepository<LinkReadLog, UUID>, LinkReadLogRepositoryCustom {
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
