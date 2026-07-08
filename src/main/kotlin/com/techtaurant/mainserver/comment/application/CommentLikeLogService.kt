package com.techtaurant.mainserver.comment.application

import com.techtaurant.mainserver.comment.entity.Comment
import com.techtaurant.mainserver.comment.entity.CommentLikeLog
import com.techtaurant.mainserver.comment.enums.CommentStatus
import com.techtaurant.mainserver.comment.infrastructure.out.CommentLikeLogRepository
import com.techtaurant.mainserver.comment.infrastructure.out.CommentRepository
import com.techtaurant.mainserver.common.enums.LikeStatus
import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.notification.application.NotificationWriteService
import com.techtaurant.mainserver.user.enums.UserStatus
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

/**
 * 댓글 좋아요/싫어요 로그 생성 및 수정 서비스
 * 사용자의 댓글 평가 이벤트를 기록하여 실시간 통계 집계를 지원합니다.
 */
@Service
class CommentLikeLogService(
    private val commentLikeLogRepository: CommentLikeLogRepository,
    private val commentRepository: CommentRepository,
    private val userRepository: UserRepository,
    private val notificationWriteService: NotificationWriteService,
) {
    /**
     * 댓글 좋아요 상태를 기록합니다.
     * LikeStatus에 따라 로그를 생성, 수정, 또는 삭제합니다.
     *
     * @param commentId 평가할 댓글 ID
     * @param userId 평가한 사용자 ID
     * @param likeStatus 좋아요 상태 (NONE: 취소, LIKE: 좋아요, DISLIKE: 싫어요)
     * @throws ApiException 댓글 또는 사용자가 존재하지 않는 경우
     */
    @Transactional
    fun recordLike(
        commentId: UUID,
        userId: UUID,
        likeStatus: LikeStatus,
    ) {
        val comment =
            commentRepository.findById(commentId).orElseThrow {
                ApiException(CommentStatus.COMMENT_NOT_FOUND)
            }

        if (comment.deletedAt != null) {
            throw ApiException(CommentStatus.COMMENT_ALREADY_DELETED)
        }

        val user =
            userRepository.findById(userId).orElseThrow {
                ApiException(UserStatus.ID_NOT_FOUND)
            }

        val existingLog = commentLikeLogRepository.findByCommentIdAndUserIdForUpdate(commentId, userId)

        if (existingLog != null) {
            val previousIsLiked = existingLog.isLiked

            when (likeStatus) {
                LikeStatus.NONE -> {
                    commentLikeLogRepository.delete(existingLog)
                    updateLikeCount(commentId, !previousIsLiked)
                    if (previousIsLiked) {
                        // LIKE → NONE: 좋아요 알림 제거
                        notificationWriteService.deleteCommentLikeNotification(actorUserId = userId, commentId = commentId)
                    }
                }
                LikeStatus.LIKE -> {
                    if (!previousIsLiked) {
                        existingLog.isLiked = true
                        commentLikeLogRepository.save(existingLog)
                        updateLikeCount(commentId, true)
                        updateLikeCount(commentId, true)
                        notifyCommentLiked(comment, userId)
                    }
                }
                LikeStatus.DISLIKE -> {
                    if (previousIsLiked) {
                        existingLog.isLiked = false
                        commentLikeLogRepository.save(existingLog)
                        updateLikeCount(commentId, false)
                        updateLikeCount(commentId, false)
                        // LIKE → DISLIKE: 좋아요 알림 제거
                        notificationWriteService.deleteCommentLikeNotification(actorUserId = userId, commentId = commentId)
                    }
                }
            }
        } else {
            when (likeStatus) {
                LikeStatus.NONE -> { }
                LikeStatus.LIKE -> {
                    commentLikeLogRepository.save(CommentLikeLog(comment = comment, user = user, isLiked = true))
                    updateLikeCount(commentId, true)
                    notifyCommentLiked(comment, userId)
                }
                LikeStatus.DISLIKE -> {
                    commentLikeLogRepository.save(CommentLikeLog(comment = comment, user = user, isLiked = false))
                    updateLikeCount(commentId, false)
                }
            }
        }
    }

    /**
     * 좋아요 상태 변경에 따라 Comment의 likeCount를 원자적으로 갱신합니다.
     *
     * @param commentId 댓글 ID
     * @param increment true이면 증가, false이면 감소
     */
    private fun updateLikeCount(
        commentId: UUID,
        increment: Boolean,
    ) {
        if (increment) {
            commentRepository.incrementLikeCount(commentId)
        } else {
            commentRepository.decrementLikeCount(commentId)
        }
    }

    /**
     * 댓글 작성자에게 좋아요 알림을 생성합니다. 본인 댓글 좋아요는 알림을 생성하지 않습니다.
     */
    private fun notifyCommentLiked(
        comment: Comment,
        actorUserId: UUID,
    ) {
        val authorId = comment.author.id ?: return
        if (authorId == actorUserId) {
            return
        }
        notificationWriteService.createCommentLikeNotification(
            actorUserId = actorUserId,
            recipientUserId = authorId,
            postId = comment.post.id!!,
            commentId = comment.id!!,
        )
    }
}
