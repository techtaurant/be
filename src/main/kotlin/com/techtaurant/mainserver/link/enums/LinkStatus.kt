package com.techtaurant.mainserver.link.enums

import com.techtaurant.mainserver.common.status.StatusIfs
import org.springframework.http.HttpStatus

enum class LinkStatus(
    private val httpStatusCode: Int,
    private val customStatusCode: Int,
    private val description: String,
) : StatusIfs {
    LINK_NOT_FOUND(HttpStatus.NOT_FOUND.value(), 6001, "링크를 찾을 수 없습니다"),
    LINK_CRAWL_BATCH_NOT_FOUND(HttpStatus.NOT_FOUND.value(), 6002, "링크 수집 배치를 찾을 수 없습니다"),
    INVALID_LINK_CRAWL_BATCH_CRON_EXPRESSION(HttpStatus.BAD_REQUEST.value(), 6004, "유효한 cron 표현식이 아닙니다"),
    INVALID_LINK_CURSOR(HttpStatus.BAD_REQUEST.value(), 6005, "유효한 링크 커서가 아닙니다"),
    LINK_CRAWL_BATCH_CREATED_AT_REQUIRED(HttpStatus.BAD_REQUEST.value(), 6006, "링크 수집 배치에서 생성일을 수집할 수 없습니다"),
    LINK_CRAWL_BATCH_NOT_CRAWLABLE(HttpStatus.BAD_REQUEST.value(), 6007, "링크 수집 배치를 크롤링할 수 없습니다"),
    LINK_CRAWL_RUN_NOT_FOUND(HttpStatus.NOT_FOUND.value(), 6008, "링크 수집 실행 이력을 찾을 수 없습니다"),
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
