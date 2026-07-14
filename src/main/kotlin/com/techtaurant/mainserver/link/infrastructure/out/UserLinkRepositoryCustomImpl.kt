package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.jooq.tables.UserLinks.Companion.USER_LINKS
import com.techtaurant.mainserver.jooq.tables.records.UserLinksRecord
import com.techtaurant.mainserver.link.entity.Link
import com.techtaurant.mainserver.link.entity.UserLink
import com.techtaurant.mainserver.user.entity.User
import jakarta.persistence.EntityManager
import org.jooq.DSLContext
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class UserLinkRepositoryCustomImpl(
    private val dsl: DSLContext,
    private val entityManager: EntityManager,
) : UserLinkRepositoryCustom {
    override fun findByUserIdAndLinkId(
        userId: UUID,
        linkId: UUID,
    ): UserLink? = query(userId, linkId).fetchOne()?.toUserLink()

    override fun findAllByUserId(userId: UUID): List<UserLink> =
        dsl.selectFrom(USER_LINKS).where(USER_LINKS.USER_ID.eq(userId)).fetch().map { it.toUserLink() }

    override fun findByUserIdAndLinkIdForUpdate(
        userId: UUID,
        linkId: UUID,
    ): UserLink? = query(userId, linkId).forUpdate().fetchOne()?.toUserLink()

    override fun findByUserIdAndLinkIdIn(
        userId: UUID,
        linkIds: List<UUID>,
    ): List<UserLink> =
        if (linkIds.isEmpty()) {
            emptyList()
        } else {
            dsl.selectFrom(USER_LINKS)
                .where(USER_LINKS.USER_ID.eq(userId).and(USER_LINKS.LINK_ID.`in`(linkIds)))
                .fetch()
                .map { it.toUserLink() }
        }

    override fun findFirstSourceByLinkId(
        linkId: UUID,
        pageable: Pageable,
    ): List<UserLink> =
        dsl.selectFrom(USER_LINKS)
            .where(USER_LINKS.LINK_ID.eq(linkId))
            .orderBy(USER_LINKS.CREATED_AT_UTC.asc(), USER_LINKS.ID.asc())
            .limit(pageable.pageSize)
            .offset(pageable.offset)
            .fetch()
            .map { it.toUserLink() }

    private fun query(
        userId: UUID,
        linkId: UUID,
    ) = dsl.selectFrom(USER_LINKS).where(USER_LINKS.USER_ID.eq(userId).and(USER_LINKS.LINK_ID.eq(linkId)))

    private fun UserLinksRecord.toUserLink(): UserLink =
        UserLink(
            entityManager.getReference(User::class.java, requireNotNull(userId)),
            entityManager.getReference(Link::class.java, requireNotNull(linkId)),
        ).apply {
            id = requireNotNull(this@toUserLink.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }
}
