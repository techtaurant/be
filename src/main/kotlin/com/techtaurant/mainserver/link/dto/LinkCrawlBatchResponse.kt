package com.techtaurant.mainserver.link.dto

import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "링크 수집 배치 응답")
data class LinkCrawlBatchResponse(
    @field:Schema(description = "배치 ID")
    val id: UUID,
    @field:Schema(description = "회사 사용자 ID")
    val companyUserId: UUID,
    @field:Schema(description = "배치 이름")
    val name: String,
    @field:Schema(description = "기준 base URL")
    val baseUrl: String,
    @field:Schema(description = "페이지 URI 템플릿")
    val pageUriTemplate: String,
    @field:Schema(description = "카드 selector")
    val itemSelector: String,
    @field:Schema(description = "링크 selector")
    val articleLinkSelector: String,
    @field:Schema(description = "제목 selector")
    val titleSelector: String,
    @field:Schema(description = "요약 selector", nullable = true)
    val summarySelector: String?,
    val createdAtSelectors: List<String>,
    val tagNames: List<String>,
    val cronExpression: String,
    val startPage: Int,
    val endPage: Int,
    val active: Boolean,
    val lastTriggeredAt: Instant?,
) {
    companion object {
        fun from(batch: LinkCrawlBatch): LinkCrawlBatchResponse {
            return LinkCrawlBatchResponse(
                id = batch.id ?: throw IllegalStateException("배치 ID가 없습니다"),
                companyUserId = batch.companyUser.id ?: throw IllegalStateException("회사 사용자 ID가 없습니다"),
                name = batch.name,
                baseUrl = batch.baseUrl,
                pageUriTemplate = batch.pageUriTemplate,
                itemSelector = batch.itemSelector,
                articleLinkSelector = batch.articleLinkSelector,
                titleSelector = batch.titleSelector,
                summarySelector = batch.summarySelector,
                createdAtSelectors = batch.createdAtSelectors.toLineList(),
                tagNames = batch.tagNames.toLineList(),
                cronExpression = batch.cronExpression,
                startPage = batch.startPage,
                endPage = batch.endPage,
                active = batch.active,
                lastTriggeredAt = batch.lastTriggeredAt,
            )
        }

        private fun String?.toLineList(): List<String> {
            return this?.lineSequence()
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.toList()
                ?: emptyList()
        }
    }
}
