package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.link.dto.CreateLinkCrawlBatchRequest
import com.techtaurant.mainserver.link.dto.UpdateLinkCrawlBatchRequest
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

    fun applyToEntity(
        request: UpdateLinkCrawlBatchRequest,
        batch: LinkCrawlBatch,
    ) {
        request.name?.let { batch.name = it.trim() }
        request.baseUrl?.let { batch.baseUrl = it.trim() }
        request.pageUriTemplate?.let { batch.pageUriTemplate = it.trim() }
        request.itemSelector?.let { batch.itemSelector = it.trim() }
        request.articleLinkSelector?.let { batch.articleLinkSelector = it.trim() }
        request.titleSelector?.let { batch.titleSelector = it.trim() }
        request.summarySelector?.let { batch.summarySelector = it.trim().takeIf(String::isNotEmpty) }
        request.createdAtSelectors?.let { batch.createdAtSelectors = normalizeLines(it) }
        request.tagNames?.let { batch.tagNames = normalizeLines(it) }
        request.cronExpression?.let { batch.cronExpression = it.trim() }
        request.startPage?.let { batch.startPage = it }
        request.endPage?.let { batch.endPage = it }
        request.active?.let { batch.active = it }
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
