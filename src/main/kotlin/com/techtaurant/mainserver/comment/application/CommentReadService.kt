package com.techtaurant.mainserver.comment.application

import com.techtaurant.mainserver.comment.dto.CommentCursor
import com.techtaurant.mainserver.comment.dto.CommentListResponse
import com.techtaurant.mainserver.comment.entity.Comment
import com.techtaurant.mainserver.comment.enums.CommentSortType
import com.techtaurant.mainserver.comment.infrastructure.out.CommentLikeLogRepository
import com.techtaurant.mainserver.comment.infrastructure.out.CommentRepositoryCustom
import com.techtaurant.mainserver.common.dto.CursorPageResponse
import com.techtaurant.mainserver.common.enums.LikeStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 댓글 읽기 서비스
 * 댓글 조회 로직을 담당합니다.
 */
@Service
@Transactional(readOnly = true)
class CommentReadService(
    private val commentRepository: CommentRepositoryCustom,
    private val commentLikeLogRepository: CommentLikeLogRepository,
    private val commentResponseAssembler: CommentResponseAssembler,
) {
    /**
     * 부모 댓글 목록을 커서 기반 페이지네이션으로 조회합니다.
     *
     * @param postId 게시물 ID
     * @param cursor 이전 응답의 nextCursor (null이면 첫 페이지)
     * @param size 페이지 크기
     * @param sortType 정렬 기준 (LATEST, LIKE, REPLY)
     * @return 커서 기반 페이지 응답
     */
    fun getParentComments(
        postId: UUID,
        cursor: String?,
        size: Int,
        sortType: CommentSortType = CommentSortType.LATEST,
        userId: UUID? = null,
    ): CursorPageResponse<CommentListResponse> {
        val commentPage =
            getCommentPage(cursor, size, sortType) { commentCursor, pageSize ->
                commentRepository.findParentCommentsIncludingDeletedWithConditions(
                    postId = postId,
                    cursor = commentCursor,
                    size = pageSize,
                    sortType = sortType,
                )
            }

        return CursorPageResponse(
            content = mapCommentsWithLikeStatus(commentPage.content, userId),
            nextCursor = commentPage.nextCursor,
            hasNext = commentPage.hasNext,
            size = commentPage.size,
        )
    }

    /**
     * 대댓글 목록을 커서 기반 페이지네이션으로 조회합니다.
     *
     * @param parentId 부모 댓글 ID
     * @param cursor 이전 응답의 nextCursor (null이면 첫 페이지)
     * @param size 페이지 크기
     * @param sortType 정렬 기준 (LATEST, LIKE, REPLY)
     * @return 커서 기반 페이지 응답
     */
    fun getReplies(
        parentId: UUID,
        cursor: String?,
        size: Int,
        sortType: CommentSortType = CommentSortType.LATEST,
        userId: UUID? = null,
    ): CursorPageResponse<CommentListResponse> {
        val commentPage =
            getCommentPage(cursor, size, sortType) { commentCursor, pageSize ->
                commentRepository.findRepliesIncludingDeletedWithConditions(
                    parentId = parentId,
                    cursor = commentCursor,
                    size = pageSize,
                    sortType = sortType,
                )
            }

        return CursorPageResponse(
            content = mapCommentsWithLikeStatus(commentPage.content, userId),
            nextCursor = commentPage.nextCursor,
            hasNext = commentPage.hasNext,
            size = commentPage.size,
        )
    }

    private fun getCommentPage(
        cursor: String?,
        size: Int,
        sortType: CommentSortType,
        findComments: (CommentCursor?, Int) -> List<Comment>,
    ): CursorPageResponse<Comment> {
        val commentCursor = cursor?.let { CommentCursor.decode(it) }

        if (cursor != null && commentCursor == null) {
            return CursorPageResponse(
                content = emptyList(),
                nextCursor = null,
                hasNext = false,
                size = 0,
            )
        }

        val comments = findComments(commentCursor, size + 1)

        val hasNext = comments.size > size
        val content = comments.take(size)

        val nextCursor =
            if (hasNext && content.isNotEmpty()) {
                CommentCursor.from(content.last(), sortType).encode()
            } else {
                null
            }

        return CursorPageResponse(
            content = content,
            nextCursor = nextCursor,
            hasNext = hasNext,
            size = content.size,
        )
    }

    /**
     * 댓글 목록에 사용자의 좋아요 상태를 매핑합니다.
     *
     * @param comments 댓글 목록
     * @param userId 사용자 ID (비회원인 경우 null)
     * @return 좋아요 상태가 포함된 응답 목록
     */
    private fun mapCommentsWithLikeStatus(
        comments: List<Comment>,
        userId: UUID?,
    ): List<CommentListResponse> {
        if (comments.isEmpty()) {
            return emptyList()
        }

        val likeStatusMap =
            if (userId == null) {
                emptyMap()
            } else {
                val commentIds = comments.map { it.id!! }
                commentLikeLogRepository.findByCommentIdInAndUserId(commentIds, userId)
                    .associate { log ->
                        log.comment.id!! to if (log.isLiked) LikeStatus.LIKE else LikeStatus.DISLIKE
                    }
            }

        return commentResponseAssembler.assemble(comments, likeStatusMap, userId)
    }
}
