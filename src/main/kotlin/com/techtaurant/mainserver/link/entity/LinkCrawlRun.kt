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
) : EntityBase()
