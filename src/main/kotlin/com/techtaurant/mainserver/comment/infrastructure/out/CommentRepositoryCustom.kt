package com.techtaurant.mainserver.comment.infrastructure.out

import com.techtaurant.mainserver.comment.dto.CommentCursor
import com.techtaurant.mainserver.comment.entity.Comment
import com.techtaurant.mainserver.comment.enums.CommentSortType
import java.util.Optional
import java.util.UUID

/**
 * 댓글 동적 쿼리를 위한 커스텀 Repository
 */
interface CommentRepositoryCustom {
    fun findByPostIdAndDeletedAtIsNullOrderByCreatedAtAsc(postId: UUID): List<Comment>

    fun findById(id: UUID): Optional<Comment>

    fun existsById(id: UUID): Boolean

    /**
     * 삭제된 댓글을 포함해 댓글 ID 목록에 해당하는 댓글을 조회합니다.
     *
     * @param commentIds 댓글 ID 목록
     * @return 댓글 목록
     */
    fun findCommentsByIdsIncludingDeleted(commentIds: List<UUID>): List<Comment>

    /**
     * 삭제된 댓글을 포함해 부모 댓글 목록을 조회합니다. (depth=0)
     *
     * @param postId 게시물 ID
     * @param cursor 커서 (null이면 첫 페이지)
     * @param size 페이지 크기
     * @param sortType 정렬 타입
     * @return 부모 댓글 목록
     */
    fun findParentCommentsIncludingDeletedWithConditions(
        postId: UUID,
        cursor: CommentCursor?,
        size: Int,
        sortType: CommentSortType,
    ): List<Comment>

    /**
     * 삭제된 댓글을 포함해 대댓글 목록을 조회합니다. (depth=1)
     *
     * @param parentId 부모 댓글 ID
     * @param cursor 커서 (null이면 첫 페이지)
     * @param size 페이지 크기
     * @param sortType 정렬 타입
     * @return 대댓글 목록
     */
    fun findRepliesIncludingDeletedWithConditions(
        parentId: UUID,
        cursor: CommentCursor?,
        size: Int,
        sortType: CommentSortType,
    ): List<Comment>
}
