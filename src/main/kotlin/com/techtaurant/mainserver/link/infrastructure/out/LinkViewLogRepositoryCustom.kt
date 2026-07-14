package com.techtaurant.mainserver.link.infrastructure.out

import java.util.UUID

interface LinkViewLogRepositoryCustom {
    fun findDistinctLinkIdsByUserIdAndLinkIdIn(
        userId: UUID,
        linkIds: List<UUID>,
    ): List<UUID>
}
