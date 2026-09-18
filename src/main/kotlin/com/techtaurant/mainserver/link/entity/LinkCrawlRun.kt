package com.techtaurant.mainserver.link.entity

import com.techtaurant.mainserver.common.base.EntityBase
import com.techtaurant.mainserver.link.enums.LinkCrawlRunStatus
import com.techtaurant.mainserver.link.enums.LinkCrawlRunTriggerType
import java.time.Instant

class LinkCrawlRun(
    var batch: LinkCrawlBatch,
    var triggerType: LinkCrawlRunTriggerType,
    var status: LinkCrawlRunStatus,
    var collectedCount: Int = 0,
    var newLinkCount: Int = 0,
    var existingLinkCount: Int = 0,
    var skippedCount: Int = 0,
    var failedJobCount: Int = 0,
    var errorStatusCode: Int? = null,
    var errorMessage: String? = null,
    var startedAt: Instant,
    var finishedAt: Instant,
) : EntityBase() {
    /**
     * 저장된 status는 실행이 끝났을 때의 결과와 이후 이월 여부만 담는다.
     * 해소는 재시도, 정기 실행, 링크 직접 등록 어디서든 일어나므로 저장하지 않고, 조회할 때 이 실행에 연결된 미해소 실패 잡으로 판단한다.
     */
    fun currentStatus(hasUnresolvedFailedJobs: Boolean): LinkCrawlRunStatus =
        when {
            hasUnresolvedFailedJobs -> LinkCrawlRunStatus.UNRESOLVED
            status == LinkCrawlRunStatus.UNRESOLVED -> LinkCrawlRunStatus.RESOLVED
            else -> status
        }
}
