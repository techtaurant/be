package com.techtaurant.mainserver.comment.infrastructure.out

import com.techtaurant.mainserver.comment.entity.Comment
import com.techtaurant.mainserver.comment.entity.CommentLikeLog
import com.techtaurant.mainserver.jooq.tables.CommentLikeLog.Companion.COMMENT_LIKE_LOG
import com.techtaurant.mainserver.jooq.tables.records.CommentLikeLogRecord
import com.techtaurant.mainserver.user.entity.User
import jakarta.persistence.EntityManager
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class CommentLikeLogRepositoryCustomImpl(
    private val dsl: DSLContext,
    private val entityManager: EntityManager,
) : CommentLikeLogRepositoryCustom {
    override fun findByCommentIdAndUserId(
        commentId: UUID,
        userId: UUID,
    ): CommentLikeLog? = query(commentId, userId).fetchOne()?.toCommentLikeLog()

    override fun findByCommentIdAndUserIdForUpdate(
        commentId: UUID,
        userId: UUID,
    ): CommentLikeLog? = query(commentId, userId).forUpdate().fetchOne()?.toCommentLikeLog()

    override fun findByCommentIdInAndUserId(
        commentIds: List<UUID>,
        userId: UUID,
    ): List<CommentLikeLog> =
        if (commentIds.isEmpty()) {
            emptyList()
        } else {
            dsl.selectFrom(COMMENT_LIKE_LOG)
                .where(COMMENT_LIKE_LOG.COMMENT_ID.`in`(commentIds).and(COMMENT_LIKE_LOG.USER_ID.eq(userId)))
                .fetch()
                .map { it.toCommentLikeLog() }
        }

    private fun query(
        commentId: UUID,
        userId: UUID,
    ) = dsl.selectFrom(COMMENT_LIKE_LOG)
        .where(COMMENT_LIKE_LOG.COMMENT_ID.eq(commentId).and(COMMENT_LIKE_LOG.USER_ID.eq(userId)))

    private fun CommentLikeLogRecord.toCommentLikeLog(): CommentLikeLog =
        CommentLikeLog(
            entityManager.getReference(Comment::class.java, requireNotNull(commentId)),
            entityManager.getReference(User::class.java, requireNotNull(userId)),
            requireNotNull(isLiked),
        ).apply {
            id = requireNotNull(this@toCommentLikeLog.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }
}
