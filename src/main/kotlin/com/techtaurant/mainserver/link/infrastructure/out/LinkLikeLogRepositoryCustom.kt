package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkLikeLog
import java.util.UUID

interface LinkLikeLogRepositoryCustom {
    fun save(log: LinkLikeLog): LinkLikeLog

    fun saveAndFlush(log: LinkLikeLog): LinkLikeLog

    fun delete(log: LinkLikeLog)

    fun insertIfAbsent(
        id: UUID,
        linkId: UUID,
        userId: UUID,
        isLiked: Boolean,
    ): Int

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
