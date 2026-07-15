package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.jooq.tables.PostLikeLog.Companion.POST_LIKE_LOG
import com.techtaurant.mainserver.jooq.tables.records.PostLikeLogRecord
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.entity.PostLikeLog
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
class PostLikeLogRepositoryCustomImpl(
    private val dsl: DSLContext,
) : PostLikeLogRepository {
    override fun save(log: PostLikeLog): PostLikeLog {
        val id = log.id ?: com.github.f4b6a3.uuid.UuidCreator.getTimeOrderedEpoch().also { log.id = it }
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        dsl.insertInto(POST_LIKE_LOG)
            .set(POST_LIKE_LOG.ID, id)
            .set(POST_LIKE_LOG.POST_ID, requireNotNull(log.post.id))
            .set(POST_LIKE_LOG.USER_ID, requireNotNull(log.user.id))
            .set(POST_LIKE_LOG.IS_LIKED, log.isLiked)
            .set(POST_LIKE_LOG.CREATED_AT_UTC, log.createdAt.atOffset(ZoneOffset.UTC))
            .set(POST_LIKE_LOG.UPDATED_AT_UTC, now)
            .onConflict(POST_LIKE_LOG.ID)
            .doUpdate()
            .set(POST_LIKE_LOG.IS_LIKED, log.isLiked)
            .set(POST_LIKE_LOG.UPDATED_AT_UTC, now)
            .execute()
        log.updatedAt = now.toInstant()
        return log
    }

    override fun delete(log: PostLikeLog) {
        log.id?.let { dsl.deleteFrom(POST_LIKE_LOG).where(POST_LIKE_LOG.ID.eq(it)).execute() }
    }

    override fun deleteAllInBatch() {
        dsl.deleteFrom(POST_LIKE_LOG).execute()
    }

    override fun findById(id: UUID): Optional<PostLikeLog> =
        Optional.ofNullable(dsl.selectFrom(POST_LIKE_LOG).where(POST_LIKE_LOG.ID.eq(id)).fetchOne()?.toPostLikeLog())

    override fun findByPostIdAndUserId(
        postId: UUID,
        userId: UUID,
    ): PostLikeLog? = query(postId, userId).fetchOne()?.toPostLikeLog()

    override fun findByPostIdAndUserIdForUpdate(
        postId: UUID,
        userId: UUID,
    ): PostLikeLog? = query(postId, userId).forUpdate().fetchOne()?.toPostLikeLog()

    override fun findByUserIdAndPostIdIn(
        userId: UUID,
        postIds: List<UUID>,
    ): List<PostLikeLog> =
        if (postIds.isEmpty()) {
            emptyList()
        } else {
            dsl.selectFrom(POST_LIKE_LOG)
                .where(POST_LIKE_LOG.USER_ID.eq(userId).and(POST_LIKE_LOG.POST_ID.`in`(postIds)))
                .fetch()
                .map { it.toPostLikeLog() }
        }

    private fun query(
        postId: UUID,
        userId: UUID,
    ) = dsl.selectFrom(POST_LIKE_LOG).where(POST_LIKE_LOG.POST_ID.eq(postId).and(POST_LIKE_LOG.USER_ID.eq(userId)))

    private fun PostLikeLogRecord.toPostLikeLog(): PostLikeLog =
        PostLikeLog(postReference(requireNotNull(postId)), userReference(requireNotNull(userId)), requireNotNull(isLiked)).apply {
            id = requireNotNull(this@toPostLikeLog.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }

    private fun postReference(postId: UUID): Post = Post("", "", userReference(postId)).apply { id = postId }

    private fun userReference(userId: UUID): User = User("", "", OAuthProvider.GOOGLE, "", UserRole.USER, "").apply { id = userId }
}
