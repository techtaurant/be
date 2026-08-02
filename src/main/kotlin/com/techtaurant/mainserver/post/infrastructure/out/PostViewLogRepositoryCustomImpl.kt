package com.techtaurant.mainserver.post.infrastructure.out

import com.github.f4b6a3.uuid.UuidCreator
import com.techtaurant.mainserver.jooq.tables.PostViewLog.Companion.POST_VIEW_LOG
import com.techtaurant.mainserver.post.entity.PostViewLog
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Repository
class PostViewLogRepositoryCustomImpl(
    private val dsl: DSLContext,
) : PostViewLogRepository {
    override fun save(log: PostViewLog): PostViewLog {
        val id = log.id ?: UuidCreator.getTimeOrderedEpoch().also { log.id = it }
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        dsl.insertInto(POST_VIEW_LOG)
            .set(POST_VIEW_LOG.ID, id)
            .set(POST_VIEW_LOG.POST_ID, requireNotNull(log.post.id))
            .set(POST_VIEW_LOG.USER_ID, log.user?.id)
            .set(POST_VIEW_LOG.IP_ADDRESS, log.ipAddress)
            .set(POST_VIEW_LOG.USER_AGENT, log.userAgent)
            .set(POST_VIEW_LOG.CREATED_AT_UTC, log.createdAt.atOffset(ZoneOffset.UTC))
            .set(POST_VIEW_LOG.UPDATED_AT_UTC, now)
            .execute()
        log.updatedAt = now.toInstant()
        return log
    }

    override fun findDistinctPostIdsByUserIdAndPostIdIn(
        userId: UUID,
        postIds: List<UUID>,
    ): List<UUID> =
        if (postIds.isEmpty()) {
            emptyList()
        } else {
            dsl.selectDistinct(POST_VIEW_LOG.POST_ID)
                .from(POST_VIEW_LOG)
                .where(POST_VIEW_LOG.USER_ID.eq(userId).and(POST_VIEW_LOG.POST_ID.`in`(postIds)))
                .fetch(POST_VIEW_LOG.POST_ID)
                .filterNotNull()
        }
}
