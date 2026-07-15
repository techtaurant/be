package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.jooq.tables.PostReadLog.Companion.POST_READ_LOG
import com.techtaurant.mainserver.jooq.tables.records.PostReadLogRecord
import com.techtaurant.mainserver.post.entity.PostReadLog
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

@Repository
class PostReadLogRepositoryCustomImpl(
    private val dsl: DSLContext,
) : PostReadLogRepository {
    override fun save(log: PostReadLog): PostReadLog {
        val id = log.id ?: com.github.f4b6a3.uuid.UuidCreator.getTimeOrderedEpoch().also { log.id = it }
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        dsl.insertInto(POST_READ_LOG)
            .set(POST_READ_LOG.ID, id)
            .set(POST_READ_LOG.POST_ID, log.postId)
            .set(POST_READ_LOG.USER_ID, requireNotNull(log.user.id))
            .set(POST_READ_LOG.CREATED_AT_UTC, log.createdAt.atOffset(ZoneOffset.UTC))
            .set(POST_READ_LOG.UPDATED_AT_UTC, now)
            .onConflict(POST_READ_LOG.ID)
            .doUpdate()
            .set(POST_READ_LOG.UPDATED_AT_UTC, now)
            .execute()
        log.updatedAt = now.toInstant()
        return log
    }

    override fun delete(log: PostReadLog) {
        log.id?.let { dsl.deleteFrom(POST_READ_LOG).where(POST_READ_LOG.ID.eq(it)).execute() }
    }

    override fun deleteAllInBatch() {
        dsl.deleteFrom(POST_READ_LOG).execute()
    }

    override fun findById(id: UUID): Optional<PostReadLog> =
        Optional.ofNullable(dsl.selectFrom(POST_READ_LOG).where(POST_READ_LOG.ID.eq(id)).fetchOne()?.toPostReadLog())

    override fun findByPostIdAndUserId(
        postId: UUID,
        userId: UUID,
    ): PostReadLog? =
        flushThen {
            dsl.selectFrom(
                POST_READ_LOG,
            ).where(POST_READ_LOG.POST_ID.eq(postId).and(POST_READ_LOG.USER_ID.eq(userId))).fetchOne()?.toPostReadLog()
        }

    override fun existsByPostIdAndUserId(
        postId: UUID,
        userId: UUID,
    ): Boolean = flushThen { dsl.fetchExists(POST_READ_LOG, POST_READ_LOG.POST_ID.eq(postId).and(POST_READ_LOG.USER_ID.eq(userId))) }

    override fun findByUserIdAndPostIdIn(
        userId: UUID,
        postIds: List<UUID>,
    ): List<PostReadLog> =
        if (postIds.isEmpty()) {
            emptyList()
        } else {
            flushThen {
                dsl.selectFrom(POST_READ_LOG).where(POST_READ_LOG.USER_ID.eq(userId).and(POST_READ_LOG.POST_ID.`in`(postIds))).fetch().map {
                    it.toPostReadLog()
                }
            }
        }

    private fun <T> flushThen(query: () -> T): T = query()

    private fun PostReadLogRecord.toPostReadLog(): PostReadLog =
        PostReadLog(requireNotNull(postId), userReference(requireNotNull(userId))).apply {
            id = requireNotNull(this@toPostReadLog.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }

    private fun userReference(userId: UUID): User = User("", "", OAuthProvider.GOOGLE, "", UserRole.USER, "").apply { id = userId }
}
