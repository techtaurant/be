package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.link.entity.LinkCrawlFailedJob
import java.time.Instant

data class LinkSnapshot(
    val title: String,
    val url: String,
    val summary: String,
    val createdAt: Instant,
) {
    fun toFailedJobDraft(): LinkFailedJobDraft =
        LinkFailedJobDraft(
            articleUrl = LinkCrawlFailedJob.truncateUrl(url),
            title = LinkCrawlFailedJob.truncateTitle(title),
            summary = summary.takeIf(String::isNotBlank),
        )
}

data class LinkFailedJobDraft(
    val articleUrl: String,
    val title: String?,
    val summary: String?,
) {
    fun toPersistableFailedJobDraft(): LinkFailedJobDraft =
        copy(
            articleUrl = LinkCrawlFailedJob.truncateUrl(articleUrl),
            title = LinkCrawlFailedJob.truncateTitle(title),
        )
}

data class LinkFailedJobRecord(
    val draft: LinkFailedJobDraft,
    val sourcePage: Int,
    val sourcePageUrl: String,
    val exception: Throwable,
)
