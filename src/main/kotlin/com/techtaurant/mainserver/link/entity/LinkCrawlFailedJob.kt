package com.techtaurant.mainserver.link.entity

import com.techtaurant.mainserver.common.base.EntityBase
import java.time.Instant

class LinkCrawlFailedJob(
    var run: LinkCrawlRun,
    var articleUrl: String,
    var errorStatusCode: Int,
    var errorMessage: String,
    var failureCount: Int = 1,
    var resolvedAt: Instant? = null,
    var lastFailedAt: Instant = Instant.now(),
) : EntityBase() {
    companion object {
        const val URL_MAX_LENGTH = Link.URL_MAX_LENGTH

        fun truncateUrl(url: String): String = url.take(URL_MAX_LENGTH)
    }
}
