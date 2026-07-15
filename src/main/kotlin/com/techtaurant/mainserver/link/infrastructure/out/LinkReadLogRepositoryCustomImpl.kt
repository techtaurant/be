package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.jooq.tables.LinkReadLog.Companion.LINK_READ_LOG
import com.techtaurant.mainserver.jooq.tables.records.LinkReadLogRecord
import com.techtaurant.mainserver.link.entity.Link
import com.techtaurant.mainserver.link.entity.LinkReadLog
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import jakarta.persistence.EntityManager
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Repository
class LinkReadLogRepositoryCustomImpl(
    private val dsl: DSLContext,
    private val entityManager: EntityManager,
) : LinkReadLogRepositoryCustom {
    override fun save(log: LinkReadLog): LinkReadLog {
        val id = log.id ?: com.github.f4b6a3.uuid.UuidCreator.getTimeOrderedEpoch().also { log.id = it }
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        dsl.insertInto(LINK_READ_LOG)
            .set(LINK_READ_LOG.ID, id)
            .set(LINK_READ_LOG.USER_ID, requireNotNull(log.user.id))
            .set(LINK_READ_LOG.LINK_ID, requireNotNull(log.link.id))
            .set(LINK_READ_LOG.CREATED_AT_UTC, log.createdAt.atOffset(ZoneOffset.UTC))
            .set(LINK_READ_LOG.UPDATED_AT_UTC, now)
            .onConflict(LINK_READ_LOG.ID)
            .doUpdate()
            .set(LINK_READ_LOG.UPDATED_AT_UTC, now)
            .execute()
        log.updatedAt = now.toInstant()
        return log
    }

    override fun delete(log: LinkReadLog) {
        log.id?.let { dsl.deleteFrom(LINK_READ_LOG).where(LINK_READ_LOG.ID.eq(it)).execute() }
    }

    override fun findByUserIdAndLinkId(
        userId: UUID,
        linkId: UUID,
    ): LinkReadLog? =
        flushThen {
            dsl.selectFrom(
                LINK_READ_LOG,
            ).where(LINK_READ_LOG.USER_ID.eq(userId).and(LINK_READ_LOG.LINK_ID.eq(linkId))).fetchOne()?.toLinkReadLog()
        }

    override fun existsByUserIdAndLinkId(
        userId: UUID,
        linkId: UUID,
    ): Boolean = flushThen { dsl.fetchExists(LINK_READ_LOG, LINK_READ_LOG.USER_ID.eq(userId).and(LINK_READ_LOG.LINK_ID.eq(linkId))) }

    override fun findByUserIdAndLinkIdIn(
        userId: UUID,
        linkIds: List<UUID>,
    ): List<LinkReadLog> =
        if (linkIds.isEmpty()) {
            emptyList()
        } else {
            flushThen {
                dsl.selectFrom(LINK_READ_LOG).where(LINK_READ_LOG.USER_ID.eq(userId).and(LINK_READ_LOG.LINK_ID.`in`(linkIds))).fetch().map {
                    it.toLinkReadLog()
                }
            }
        }

    private fun <T> flushThen(query: () -> T): T {
        if (entityManager.isJoinedToTransaction) {
            entityManager.flush()
        }
        return query()
    }

    private fun LinkReadLogRecord.toLinkReadLog(): LinkReadLog =
        LinkReadLog(userReference(requireNotNull(userId)), linkReference(requireNotNull(linkId))).apply {
            id = requireNotNull(this@toLinkReadLog.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }

    private fun userReference(userId: UUID): User = User("", "", OAuthProvider.GOOGLE, "", UserRole.USER, "").apply { id = userId }

    private fun linkReference(linkId: UUID): Link = Link("", "", "").apply { id = linkId }
}
