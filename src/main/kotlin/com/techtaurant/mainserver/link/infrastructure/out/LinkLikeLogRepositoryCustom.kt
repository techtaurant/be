package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkLikeLog
import java.util.UUID

interface LinkLikeLogRepositoryCustom {
    fun findByLinkIdAndUserId(
        linkId: UUID,
        userId: UUID,
    ): LinkLikeLog?

    fun findByLinkIdAndUserIdForUpdate(
        linkId: UUID,
        userId: UUID,
    ): LinkLikeLog?

    fun findByUserIdAndLinkIdIn(
        userId: UUID,
        linkIds: List<UUID>,
    ): List<LinkLikeLog>
}
