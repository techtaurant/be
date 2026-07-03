package com.techtaurant.mainserver.link.dto

import com.techtaurant.mainserver.link.entity.LinkCrawlRun
import com.techtaurant.mainserver.link.enums.LinkCrawlRunStatus
import com.techtaurant.mainserver.link.enums.LinkCrawlRunTriggerType
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "링크 수집 실행(run) 이력 응답")
data class LinkCrawlRunResponse(
    @field:Schema(description = "실행 이력 ID")
    val id: UUID,
    @field:Schema(description = "배치 ID")
    val batchId: UUID,
    @field:Schema(description = "실행 트리거 유형")
    val triggerType: LinkCrawlRunTriggerType,
    @field:Schema(description = "실행 상태 (COMPLETED: 실패 없음, UNRESOLVED: 미해소 실패 잡 존재, RESOLVED: 재시도로 모두 해소, FAILED: 실행 자체 실패)")
    val status: LinkCrawlRunStatus,
    @field:Schema(description = "정상 파싱된 링크 수")
    val collectedCount: Int,
    @field:Schema(description = "새로 생성된 링크 수")
    val newLinkCount: Int,
    @field:Schema(description = "기존 링크를 재사용한 수")
    val existingLinkCount: Int,
    @field:Schema(description = "selector 미일치 등으로 건너뛴 수")
    val skippedCount: Int,
    @field:Schema(description = "실패 잡으로 기록된 링크 수")
    val failedJobCount: Int,
    @field:Schema(description = "실행 자체 실패 시 오류 상태 코드")
    val errorStatusCode: Int?,
    @field:Schema(description = "실행 자체 실패 시 오류 메시지")
    val errorMessage: String?,
    @field:Schema(description = "아직 해소되지 않은 실패 잡 존재 여부")
    val hasUnresolvedFailedJobs: Boolean,
    @field:Schema(description = "실행 시작 시각")
    val startedAt: Instant,
    @field:Schema(description = "실행 종료 시각")
    val finishedAt: Instant,
    @field:Schema(description = "이력 생성 시각")
    val createdAt: Instant,
) {
    companion object {
        fun from(
            run: LinkCrawlRun,
            hasUnresolvedFailedJobs: Boolean,
        ): LinkCrawlRunResponse {
            return LinkCrawlRunResponse(
                id = run.id!!,
                batchId = run.batch.id!!,
                triggerType = run.triggerType,
                status = run.status,
                collectedCount = run.collectedCount,
                newLinkCount = run.newLinkCount,
                existingLinkCount = run.existingLinkCount,
                skippedCount = run.skippedCount,
                failedJobCount = run.failedJobCount,
                errorStatusCode = run.errorStatusCode,
                errorMessage = run.errorMessage,
                hasUnresolvedFailedJobs = hasUnresolvedFailedJobs,
                startedAt = run.startedAt,
                finishedAt = run.finishedAt,
                createdAt = run.createdAt,
            )
        }
    }
}
