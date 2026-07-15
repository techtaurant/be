package com.techtaurant.mainserver.link.infrastructure.out

import com.github.f4b6a3.uuid.UuidCreator
import com.techtaurant.mainserver.jooq.tables.UserLinks.Companion.USER_LINKS
import com.techtaurant.mainserver.jooq.tables.records.UserLinksRecord
import com.techtaurant.mainserver.link.entity.Link
import com.techtaurant.mainserver.link.entity.UserLink
import com.techtaurant.mainserver.user.entity.User
import org.jooq.DSLContext
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Repository
class UserLinkRepositoryCustomImpl(
    private val dsl: DSLContext,
) : UserLinkRepositoryCustom {
    override fun save(userLink: UserLink): UserLink {
        val id = userLink.id ?: UuidCreator.getTimeOrderedEpoch().also { userLink.id = it }
        val now = Instant.now()
        dsl.insertInto(USER_LINKS)
            .set(
                USER_LINKS.ID,
                id,
            ).set(USER_LINKS.USER_ID, requireNotNull(userLink.user.id)).set(USER_LINKS.LINK_ID, requireNotNull(userLink.link.id))
            .set(
                USER_LINKS.CREATED_AT_UTC,
                now.atOffset(ZoneOffset.UTC),
            ).set(USER_LINKS.UPDATED_AT_UTC, now.atOffset(ZoneOffset.UTC))
            .onConflict(USER_LINKS.ID)
            .doUpdate()
            .set(USER_LINKS.UPDATED_AT_UTC, now.atOffset(ZoneOffset.UTC))
            .execute()
        userLink.createdAt = now
        userLink.updatedAt = now
        return userLink
    }

    override fun saveAndFlush(userLink: UserLink): UserLink = save(userLink)

    override fun delete(userLink: UserLink) {
        userLink.id?.let { dsl.deleteFrom(USER_LINKS).where(USER_LINKS.ID.eq(it)).execute() }
    }

    override fun deleteAllInBatch() {
        dsl.deleteFrom(USER_LINKS).execute()
    }

    override fun existsById(id: UUID): Boolean = dsl.fetchExists(USER_LINKS, USER_LINKS.ID.eq(id))

    override fun insertIfAbsent(
        id: UUID,
        userId: UUID,
        linkId: UUID,
    ): Int =
        dsl.insertInto(USER_LINKS).set(USER_LINKS.ID, id).set(USER_LINKS.USER_ID, userId).set(USER_LINKS.LINK_ID, linkId)
            .set(
                USER_LINKS.CREATED_AT_UTC,
                Instant.now().atOffset(ZoneOffset.UTC),
            ).set(USER_LINKS.UPDATED_AT_UTC, Instant.now().atOffset(ZoneOffset.UTC))
            .onConflict(USER_LINKS.USER_ID, USER_LINKS.LINK_ID).doNothing().execute()

    override fun deleteAllByLink(link: Link): Long =
        dsl.deleteFrom(USER_LINKS).where(USER_LINKS.LINK_ID.eq(requireNotNull(link.id))).execute().toLong()

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
            User(
                "",
                "",
                com.techtaurant.mainserver.security.enums.OAuthProvider.GOOGLE,
                "",
                com.techtaurant.mainserver.user.enums.UserRole.USER,
                "",
            ).apply {
                id = requireNotNull(userId)
            },
            Link("", "https://reference.invalid", "").apply { id = requireNotNull(linkId) },
        ).apply {
            id = requireNotNull(this@toUserLink.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }
}
