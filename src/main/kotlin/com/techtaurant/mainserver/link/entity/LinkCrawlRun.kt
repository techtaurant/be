package com.techtaurant.mainserver.link.entity

import com.techtaurant.mainserver.common.base.EntityBase
import com.techtaurant.mainserver.link.enums.LinkCrawlRunStatus
import com.techtaurant.mainserver.link.enums.LinkCrawlRunTriggerType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(
    name = "link_crawl_runs",
    indexes = [
        Index(name = "idx_link_crawl_runs_batch_id_started_at", columnList = "batch_id, started_at_utc"),
    ],
)
class LinkCrawlRun(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", nullable = false)
    var batch: LinkCrawlBatch,
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    var triggerType: LinkCrawlRunTriggerType,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: LinkCrawlRunStatus,
    @Column(name = "collected_count", nullable = false)
    var collectedCount: Int = 0,
    @Column(name = "new_link_count", nullable = false)
    var newLinkCount: Int = 0,
    @Column(name = "existing_link_count", nullable = false)
    var existingLinkCount: Int = 0,
    @Column(name = "skipped_count", nullable = false)
    var skippedCount: Int = 0,
    @Column(name = "failed_job_count", nullable = false)
    var failedJobCount: Int = 0,
    @Column(name = "error_status_code")
    var errorStatusCode: Int? = null,
    @Column(name = "error_message", columnDefinition = "TEXT")
    var errorMessage: String? = null,
    @Column(name = "started_at_utc", nullable = false)
    var startedAt: Instant,
    @Column(name = "finished_at_utc", nullable = false)
    var finishedAt: Instant,
) : EntityBase()
