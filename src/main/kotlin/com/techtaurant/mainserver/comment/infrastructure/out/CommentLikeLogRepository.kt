package com.techtaurant.mainserver.comment.infrastructure.out

import com.techtaurant.mainserver.comment.entity.CommentLikeLog
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.*

interface CommentLikeLogRepository : JpaRepository<CommentLikeLog, UUID> {
    /**
     * 특정 댓글에 대한 특정 사용자의 좋아요 로그를 조회합니다.
     * 기존 로그가 있는지 확인하여 UPDATE 또는 INSERT를 결정하는 데 사용됩니다.
     *
     * @param commentId 댓글 ID
     * @param userId 사용자 ID
     * @return 좋아요 로그 (없으면 null)
     */
    fun findByCommentIdAndUserId(
        commentId: UUID,
        userId: UUID,
    ): CommentLikeLog?

    /**
     * 특정 댓글·사용자의 좋아요 로그를 행 잠금(PESSIMISTIC_WRITE)으로 조회합니다.
     * 좋아요 상태 전이를 직렬화하여 동시 요청(따닥) 시 중복 알림/중복 카운트를 방지합니다.
     *
     * @param commentId 댓글 ID
     * @param userId 사용자 ID
     * @return 좋아요 로그 (없으면 null)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM CommentLikeLog l WHERE l.comment.id = :commentId AND l.user.id = :userId")
    fun findByCommentIdAndUserIdForUpdate(
        @Param("commentId") commentId: UUID,
        @Param("userId") userId: UUID,
    ): CommentLikeLog?

    /**
     * 여러 댓글에 대한 특정 사용자의 좋아요 로그를 일괄 조회합니다.
     *
     * @param commentIds 댓글 ID 목록
     * @param userId 사용자 ID
     * @return 좋아요 로그 목록
     */
    fun findByCommentIdInAndUserId(
        commentIds: List<UUID>,
        userId: UUID,
    ): List<CommentLikeLog>
}
