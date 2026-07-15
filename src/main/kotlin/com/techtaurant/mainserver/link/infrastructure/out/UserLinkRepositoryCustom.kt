package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.Link
import com.techtaurant.mainserver.link.entity.UserLink
import org.springframework.data.domain.Pageable
import java.util.UUID

interface UserLinkRepositoryCustom {
    fun save(userLink: UserLink): UserLink

    fun saveAndFlush(userLink: UserLink): UserLink

    fun delete(userLink: UserLink)

    fun deleteAllInBatch()

    fun existsById(id: UUID): Boolean

    fun insertIfAbsent(
        id: UUID,
        userId: UUID,
        linkId: UUID,
    ): Int

    fun deleteAllByLink(link: Link): Long

    fun findByUserIdAndLinkId(
        userId: UUID,
        linkId: UUID,
    ): UserLink?

    fun findAllByUserId(userId: UUID): List<UserLink>

    fun findByUserIdAndLinkIdForUpdate(
        userId: UUID,
        linkId: UUID,
    ): UserLink?

    fun findByUserIdAndLinkIdIn(
        userId: UUID,
        linkIds: List<UUID>,
    ): List<UserLink>

    fun findFirstSourceByLinkId(
        linkId: UUID,
        pageable: Pageable,
    ): List<UserLink>
}
