package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkLikeLog
import org.springframework.data.repository.Repository
import java.util.UUID

interface LinkLikeLogRepository : Repository<LinkLikeLog, UUID>, LinkLikeLogRepositoryCustom {
    override fun save(log: LinkLikeLog): LinkLikeLog

    override fun saveAndFlush(log: LinkLikeLog): LinkLikeLog

    override fun delete(log: LinkLikeLog)

    override fun insertIfAbsent(
        id: UUID,
        linkId: UUID,
        userId: UUID,
        isLiked: Boolean,
    ): Int
}
