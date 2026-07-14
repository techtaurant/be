package com.techtaurant.mainserver.comment.infrastructure.out

import com.techtaurant.mainserver.comment.entity.CommentLikeLog
import java.util.UUID

interface CommentLikeLogRepositoryCustom {
    fun findByCommentIdAndUserId(
        commentId: UUID,
        userId: UUID,
    ): CommentLikeLog?

    fun findByCommentIdAndUserIdForUpdate(
        commentId: UUID,
        userId: UUID,
    ): CommentLikeLog?

    fun findByCommentIdInAndUserId(
        commentIds: List<UUID>,
        userId: UUID,
    ): List<CommentLikeLog>
}
