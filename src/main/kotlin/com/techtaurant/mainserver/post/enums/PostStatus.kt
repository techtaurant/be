package com.techtaurant.mainserver.post.enums

import com.techtaurant.mainserver.common.status.StatusIfs
import org.springframework.http.HttpStatus

enum class PostStatus(
    private val httpStatusCode: Int,
    private val customStatusCode: Int,
    private val description: String,
) : StatusIfs {
    POST_NOT_FOUND(HttpStatus.NOT_FOUND.value(), 2001, "게시물을 찾을 수 없습니다"),
    CATEGORY_DEPTH_EXCEEDED(HttpStatus.BAD_REQUEST.value(), 2002, "카테고리 깊이는 최대 5단계까지 가능합니다"),
    INVALID_SORT_TYPE(HttpStatus.BAD_REQUEST.value(), 2005, "유효하지 않은 정렬 타입입니다"),
    CANNOT_MODIFY_OTHERS_POST(HttpStatus.FORBIDDEN.value(), 2006, "다른 사용자의 게시물을 수정할 수 없습니다"),
    TITLE_REQUIRED(HttpStatus.BAD_REQUEST.value(), 2008, "제목은 필수입니다"),
    CONTENT_REQUIRED(HttpStatus.BAD_REQUEST.value(), 2009, "본문은 필수입니다"),
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
