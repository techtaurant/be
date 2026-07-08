package com.techtaurant.mainserver.link.dto

import com.techtaurant.mainserver.link.enums.LinkCrawlRunStatus
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "실행 이력의 미해소 실패 잡 재시도 결과")
data class LinkCrawlFailedJobRetryResponse(
    @field:Schema(description = "재시도한 실패 잡 수")
    val retriedCount: Int,
    @field:Schema(description = "재시도로 해소된 실패 잡 수")
    val resolvedCount: Int,
    @field:Schema(description = "재시도 후에도 해소되지 않은 실패 잡 수")
    val stillUnresolvedCount: Int,
    @field:Schema(description = "재시도 후 실행 이력 상태")
    val runStatus: LinkCrawlRunStatus,
)
