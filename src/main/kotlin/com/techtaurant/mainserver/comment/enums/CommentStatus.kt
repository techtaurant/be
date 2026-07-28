package com.techtaurant.mainserver.comment.enums

import com.techtaurant.mainserver.common.status.StatusIfs
import org.springframework.http.HttpStatus

/**
 * 댓글 관련 상태 코드
 */
enum class CommentStatus(
    private val httpStatusCode: Int,
    private val customStatusCode: Int,
    private val description: String,
) : StatusIfs {
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND.value(), 7001, "댓글을 찾을 수 없습니다"),
    COMMENT_PARENT_MISMATCH(HttpStatus.BAD_REQUEST.value(), 7002, "부모 댓글이 다른 게시물의 댓글입니다"),
    COMMENT_MAX_DEPTH_EXCEEDED(HttpStatus.BAD_REQUEST.value(), 7003, "대댓글의 답글은 작성할 수 없습니다"),
    COMMENT_AUTHOR_MISMATCH(HttpStatus.FORBIDDEN.value(), 7004, "댓글 작성자만 수행할 수 있습니다"),
    COMMENT_ALREADY_DELETED(HttpStatus.GONE.value(), 7005, "이미 삭제된 댓글입니다"),
    ;

    override fun getHttpStatusCode(): Int {
        return this.httpStatusCode
    }

    override fun getCustomStatusCode(): Int {
        return this.customStatusCode
    }

    override fun getDescription(): String {
        return this.description
    }
}
