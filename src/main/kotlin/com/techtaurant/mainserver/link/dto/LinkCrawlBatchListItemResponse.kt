package com.techtaurant.mainserver.link.dto

import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "링크 수집 배치 목록 조회 응답")
data class LinkCrawlBatchListItemResponse(
    @field:Schema(description = "배치 ID")
    val id: UUID,
    @field:Schema(description = "회사 사용자 ID")
    val companyUserId: UUID,
    @field:Schema(description = "배치 이름")
    val name: String,
    @field:Schema(description = "기준 base URL")
    val baseUrl: String,
    val cronExpression: String,
    val startPage: Int,
    val endPage: Int,
    val active: Boolean,
    val lastTriggeredAt: Instant?,
) {
    companion object {
        fun from(batch: LinkCrawlBatch): LinkCrawlBatchListItemResponse {
            return LinkCrawlBatchListItemResponse(
                id = batch.id ?: throw IllegalStateException("배치 ID가 없습니다"),
                companyUserId = batch.companyUser.id ?: throw IllegalStateException("회사 사용자 ID가 없습니다"),
                name = batch.name,
                baseUrl = batch.baseUrl,
                cronExpression = batch.cronExpression,
                startPage = batch.startPage,
                endPage = batch.endPage,
                active = batch.active,
                lastTriggeredAt = batch.lastTriggeredAt,
            )
        }
    }
}
