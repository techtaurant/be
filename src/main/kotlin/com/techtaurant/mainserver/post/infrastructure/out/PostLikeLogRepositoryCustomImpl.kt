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
import java.util.UUID

@Repository
class PostLikeLogRepositoryCustomImpl(
    private val dsl: DSLContext,
) : PostLikeLogRepositoryCustom {
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
