package com.techtaurant.mainserver.link.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "링크 수집 배치 실행 결과")
data class LinkBatchRunResponse(
    @field:Schema(description = "정상 파싱된 링크 수")
    val collectedCount: Int,
    @field:Schema(description = "새로 생성된 링크 수")
    val newLinkCount: Int,
    @field:Schema(description = "기존 링크를 재사용한 수")
    val existingLinkCount: Int,
    @field:Schema(description = "selector 미일치 등으로 건너뛴 수")
    val skippedCount: Int,
    @field:Schema(description = "실패 잡으로 기록된 링크 수")
    val failedJobCount: Int = 0,
)
