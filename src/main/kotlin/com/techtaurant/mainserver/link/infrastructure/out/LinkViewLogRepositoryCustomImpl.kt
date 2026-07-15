package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.jooq.tables.LinkViewLog.Companion.LINK_VIEW_LOG
import com.techtaurant.mainserver.jooq.tables.records.LinkViewLogRecord
import com.techtaurant.mainserver.link.entity.Link
import com.techtaurant.mainserver.link.entity.LinkViewLog
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Repository
class LinkViewLogRepositoryCustomImpl(
    private val dsl: DSLContext,
) : LinkViewLogRepositoryCustom {
    override fun save(log: LinkViewLog): LinkViewLog {
        val id = log.id ?: com.github.f4b6a3.uuid.UuidCreator.getTimeOrderedEpoch().also { log.id = it }
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        dsl.insertInto(LINK_VIEW_LOG)
            .set(LINK_VIEW_LOG.ID, id)
            .set(LINK_VIEW_LOG.LINK_ID, requireNotNull(log.link.id))
            .set(LINK_VIEW_LOG.USER_ID, log.user?.id)
            .set(LINK_VIEW_LOG.IP_ADDRESS, log.ipAddress)
            .set(LINK_VIEW_LOG.USER_AGENT, log.userAgent)
            .set(LINK_VIEW_LOG.CREATED_AT_UTC, log.createdAt.atOffset(ZoneOffset.UTC))
            .set(LINK_VIEW_LOG.UPDATED_AT_UTC, now)
            .execute()
        log.updatedAt = now.toInstant()
        return log
    }

    override fun findAll(): List<LinkViewLog> = dsl.selectFrom(LINK_VIEW_LOG).fetch().map { it.toLinkViewLog() }

    override fun findDistinctLinkIdsByUserIdAndLinkIdIn(
        userId: UUID,
        linkIds: List<UUID>,
    ): List<UUID> =
        if (linkIds.isEmpty()) {
            emptyList()
        } else {
            dsl.selectDistinct(LINK_VIEW_LOG.LINK_ID)
                .from(LINK_VIEW_LOG)
                .where(LINK_VIEW_LOG.USER_ID.eq(userId).and(LINK_VIEW_LOG.LINK_ID.`in`(linkIds)))
                .fetch(LINK_VIEW_LOG.LINK_ID)
                .filterNotNull()
        }

    private fun LinkViewLogRecord.toLinkViewLog(): LinkViewLog =
        LinkViewLog(
            link = Link("", "", "").apply { id = requireNotNull(linkId) },
            user = userId?.let { User("", "", OAuthProvider.GOOGLE, "", UserRole.USER, "").apply { id = it } },
            ipAddress = ipAddress,
            userAgent = userAgent,
        ).apply {
            id = requireNotNull(this@toLinkViewLog.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }
}
