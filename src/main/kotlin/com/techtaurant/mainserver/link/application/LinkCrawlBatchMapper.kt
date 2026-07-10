package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.link.dto.CreateLinkCrawlBatchRequest
import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import com.techtaurant.mainserver.user.entity.User

internal object LinkCrawlBatchMapper {
    fun toEntity(
        request: CreateLinkCrawlBatchRequest,
        companyUser: User,
    ): LinkCrawlBatch {
        return LinkCrawlBatch(
            companyUser = companyUser,
            name = request.name.trim(),
            baseUrl = request.baseUrl.trim(),
            pageUriTemplate = request.pageUriTemplate.trim(),
            itemSelector = request.itemSelector.trim(),
            articleLinkSelector = request.articleLinkSelector.trim(),
            titleSelector = request.titleSelector.trim(),
            summarySelector = request.summarySelector?.trim()?.takeIf { it.isNotEmpty() },
            createdAtSelectors = normalizeLines(request.createdAtSelectors),
            tagNames = normalizeLines(request.tagNames),
            cronExpression = request.cronExpression.trim(),
            startPage = request.startPage,
            endPage = request.endPage,
            active = request.active,
        )
    }

    private fun normalizeLines(values: List<String>): String? {
        return values.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
            .takeIf(List<String>::isNotEmpty)
            ?.joinToString("\n")
    }
}
