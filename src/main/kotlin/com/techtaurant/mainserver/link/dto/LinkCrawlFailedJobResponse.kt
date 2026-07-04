package com.techtaurant.mainserver.link.dto

import com.techtaurant.mainserver.link.entity.LinkCrawlFailedJob
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "링크 수집 실패 잡 응답")
data class LinkCrawlFailedJobResponse(
    @field:Schema(description = "실패 잡 ID")
    val id: UUID,
    @field:Schema(description = "실행 이력 ID")
    val runId: UUID,
    @field:Schema(description = "배치 ID")
    val batchId: UUID,
    @field:Schema(description = "처리에 실패한 아티클 URL")
    val articleUrl: String,
    @field:Schema(description = "마지막 실패 상태 코드")
    val errorStatusCode: Int,
    @field:Schema(description = "마지막 실패 메시지")
    val errorMessage: String,
    @field:Schema(description = "누적 실패 횟수")
    val failureCount: Int,
    @field:Schema(description = "해소 시각", nullable = true)
    val resolvedAt: Instant?,
    @field:Schema(description = "마지막 실패 시각")
    val lastFailedAt: Instant,
    @field:Schema(description = "실패 잡 생성 시각")
    val createdAt: Instant,
    @field:Schema(description = "실패 잡 수정 시각")
    val updatedAt: Instant,
) {
    companion object {
        fun from(failedJob: LinkCrawlFailedJob): LinkCrawlFailedJobResponse {
            val run = failedJob.run
            return LinkCrawlFailedJobResponse(
                id = failedJob.id!!,
                runId = run.id!!,
                batchId = run.batch.id!!,
                articleUrl = failedJob.articleUrl,
                errorStatusCode = failedJob.errorStatusCode,
                errorMessage = failedJob.errorMessage,
                failureCount = failedJob.failureCount,
                resolvedAt = failedJob.resolvedAt,
                lastFailedAt = failedJob.lastFailedAt,
                createdAt = failedJob.createdAt,
                updatedAt = failedJob.updatedAt,
            )
        }
    }
}
