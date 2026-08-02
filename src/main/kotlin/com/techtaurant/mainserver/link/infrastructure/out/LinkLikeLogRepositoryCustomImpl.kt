package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.jooq.tables.LinkLikeLog.Companion.LINK_LIKE_LOG
import com.techtaurant.mainserver.jooq.tables.records.LinkLikeLogRecord
import com.techtaurant.mainserver.link.entity.Link
import com.techtaurant.mainserver.link.entity.LinkLikeLog
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Repository
class LinkLikeLogRepositoryCustomImpl(
    private val dsl: DSLContext,
) : LinkLikeLogRepository {
    override fun save(log: LinkLikeLog): LinkLikeLog {
        val id = log.id ?: com.github.f4b6a3.uuid.UuidCreator.getTimeOrderedEpoch().also { log.id = it }
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        dsl.insertInto(LINK_LIKE_LOG)
            .set(LINK_LIKE_LOG.ID, id)
            .set(LINK_LIKE_LOG.LINK_ID, requireNotNull(log.link.id))
            .set(LINK_LIKE_LOG.USER_ID, requireNotNull(log.user.id))
            .set(LINK_LIKE_LOG.IS_LIKED, log.isLiked)
            .set(LINK_LIKE_LOG.CREATED_AT_UTC, log.createdAt.atOffset(ZoneOffset.UTC))
            .set(LINK_LIKE_LOG.UPDATED_AT_UTC, now)
            .onConflict(LINK_LIKE_LOG.ID)
            .doUpdate()
            .set(LINK_LIKE_LOG.IS_LIKED, log.isLiked)
            .set(LINK_LIKE_LOG.UPDATED_AT_UTC, now)
            .execute()
        log.updatedAt = now.toInstant()
        return log
    }

    override fun saveAndFlush(log: LinkLikeLog): LinkLikeLog = save(log)

    override fun delete(log: LinkLikeLog) {
        log.id?.let { dsl.deleteFrom(LINK_LIKE_LOG).where(LINK_LIKE_LOG.ID.eq(it)).execute() }
    }

    override fun insertIfAbsent(
        id: UUID,
        linkId: UUID,
        userId: UUID,
        isLiked: Boolean,
    ): Int =
        dsl.insertInto(LINK_LIKE_LOG)
            .set(LINK_LIKE_LOG.ID, id)
            .set(LINK_LIKE_LOG.LINK_ID, linkId)
            .set(LINK_LIKE_LOG.USER_ID, userId)
            .set(LINK_LIKE_LOG.IS_LIKED, isLiked)
            .set(LINK_LIKE_LOG.CREATED_AT_UTC, Instant.now().atOffset(ZoneOffset.UTC))
            .set(LINK_LIKE_LOG.UPDATED_AT_UTC, Instant.now().atOffset(ZoneOffset.UTC))
            .onConflict(LINK_LIKE_LOG.LINK_ID, LINK_LIKE_LOG.USER_ID)
            .doNothing()
            .execute()

    override fun findByLinkIdAndUserId(
        linkId: UUID,
        userId: UUID,
    ): LinkLikeLog? = query(linkId, userId).fetchOne()?.toLinkLikeLog()

    override fun findByLinkIdAndUserIdForUpdate(
        linkId: UUID,
        userId: UUID,
    ): LinkLikeLog? = query(linkId, userId).forUpdate().fetchOne()?.toLinkLikeLog()

    override fun findByUserIdAndLinkIdIn(
        userId: UUID,
        linkIds: List<UUID>,
    ): List<LinkLikeLog> =
        if (linkIds.isEmpty()) {
            emptyList()
        } else {
            dsl.selectFrom(LINK_LIKE_LOG)
                .where(LINK_LIKE_LOG.USER_ID.eq(userId).and(LINK_LIKE_LOG.LINK_ID.`in`(linkIds)))
                .fetch()
                .map { it.toLinkLikeLog() }
        }

    private fun query(
        linkId: UUID,
        userId: UUID,
    ) = dsl.selectFrom(LINK_LIKE_LOG).where(LINK_LIKE_LOG.LINK_ID.eq(linkId).and(LINK_LIKE_LOG.USER_ID.eq(userId)))

    private fun LinkLikeLogRecord.toLinkLikeLog(): LinkLikeLog =
        LinkLikeLog(linkReference(requireNotNull(linkId)), userReference(requireNotNull(userId)), requireNotNull(isLiked)).apply {
            id = requireNotNull(this@toLinkLikeLog.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }

    private fun linkReference(linkId: UUID): Link = Link("", "", "").apply { id = linkId }

    private fun userReference(userId: UUID): User = User("", "", OAuthProvider.GOOGLE, "", UserRole.USER, "").apply { id = userId }
}
