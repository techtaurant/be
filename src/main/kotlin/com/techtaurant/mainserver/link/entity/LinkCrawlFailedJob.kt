package com.techtaurant.mainserver.link.entity

import com.techtaurant.mainserver.common.base.EntityBase
import java.time.Instant

/**
 * 배치가 끝내 수집하지 못한 아티클 URL 하나를 나타낸다.
 * 소유자가 배치이므로 같은 URL이 여러 실행에서 실패해도 행은 하나이고 재시도 횟수가 누적된다.
 * lastRun은 마지막으로 이 URL을 실패시킨 실행이며, 그 실행 이력이 삭제되면 비어 있다.
 */
class LinkCrawlFailedJob(
    var batch: LinkCrawlBatch,
    var articleUrl: String,
    var errorStatusCode: Int,
    var errorMessage: String,
    var failureCount: Int = 1,
    var resolvedAt: Instant? = null,
    var lastFailedAt: Instant = Instant.now(),
    var lastRun: LinkCrawlRun? = null,
) : EntityBase() {
    companion object {
        const val URL_MAX_LENGTH = Link.URL_MAX_LENGTH

        fun truncateUrl(url: String): String = url.take(URL_MAX_LENGTH)
    }
}
