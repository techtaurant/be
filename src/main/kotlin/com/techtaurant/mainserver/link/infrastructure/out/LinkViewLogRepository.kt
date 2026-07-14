package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkViewLog
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LinkViewLogRepository : JpaRepository<LinkViewLog, UUID>, LinkViewLogRepositoryCustom {
    override fun findDistinctLinkIdsByUserIdAndLinkIdIn(
        userId: UUID,
        linkIds: List<UUID>,
    ): List<UUID>
}
