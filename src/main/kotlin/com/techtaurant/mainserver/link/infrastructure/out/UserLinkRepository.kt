package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.Link
import com.techtaurant.mainserver.link.entity.UserLink
import org.springframework.data.repository.Repository
import java.util.UUID

interface UserLinkRepository : Repository<UserLink, UUID>, UserLinkRepositoryCustom {
    override fun insertIfAbsent(
        id: UUID,
        userId: UUID,
        linkId: UUID,
    ): Int

    override fun deleteAllByLink(link: Link): Long
}
