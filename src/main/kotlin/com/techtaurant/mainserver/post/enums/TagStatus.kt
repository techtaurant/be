package com.techtaurant.mainserver.post.enums

import com.techtaurant.mainserver.common.status.StatusIfs
import org.springframework.http.HttpStatus

enum class TagStatus(
    private val httpStatusCode: Int,
    private val customStatusCode: Int,
    private val description: String,
) : StatusIfs {
    TAG_COUNT_EXCEEDED(HttpStatus.BAD_REQUEST.value(), 2010, "태그는 최대 10개까지 설정할 수 있습니다"),
    ;

    override fun getHttpStatusCode(): Int = httpStatusCode

    override fun getCustomStatusCode(): Int = customStatusCode

    override fun getDescription(): String = description
}
