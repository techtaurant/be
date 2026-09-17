package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.common.status.DefaultStatus
import com.techtaurant.mainserver.link.dto.LinkCrawlFailedJobRetryResponse
import com.techtaurant.mainserver.link.entity.LinkCrawlFailedJob
import com.techtaurant.mainserver.link.entity.LinkCrawlRun
import com.techtaurant.mainserver.link.enums.LinkCrawlRunStatus
import com.techtaurant.mainserver.link.enums.LinkStatus
import com.techtaurant.mainserver.link.infrastructure.out.LinkCrawlFailedJobRepository
import com.techtaurant.mainserver.link.infrastructure.out.LinkCrawlRunRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations
import java.time.Instant
import java.util.UUID

@Service
class LinkCrawlFailedJobRetryService(
    private val linkCrawlRunRepository: LinkCrawlRunRepository,
    private val linkCrawlFailedJobRepository: LinkCrawlFailedJobRepository,
    private val linkCrawlLinkCollector: LinkCrawlLinkCollector,
    private val linkDocumentFetcher: LinkDocumentFetcher,
    private val transactionOperations: TransactionOperations,
) {
    private val crawlDocumentParser = LinkCrawlDocumentParser(linkDocumentFetcher)

    fun retryRunFailedJobs(runId: UUID): LinkCrawlFailedJobRetryResponse {
        val failedJobIds = findManualRetryFailedJobIds(runId)
        val resolvedCount = failedJobIds.count { failedJobId -> retryFailedJobById(failedJobId) }
        val retrySummary = summarizeRetryRun(runId)

        return LinkCrawlFailedJobRetryResponse(
            retriedCount = failedJobIds.size,
            resolvedCount = resolvedCount,
            stillUnresolvedCount = retrySummary.stillUnresolvedCount,
            runStatus = retrySummary.runStatus,
        )
    }

    fun retryAllUnresolvedFailedJobs(now: Instant = Instant.now()) {
        val retryableJobs =
            linkCrawlFailedJobRepository.findRetryableAutomaticJobs(
                maxFailureCount = LinkCrawlFailedJobRetryPolicy.MAX_FAILURE_COUNT,
                retryableBefore = LinkCrawlFailedJobRetryPolicy.retryableBefore(now),
                pageable = LinkCrawlFailedJobRetryPolicy.pageRequest(),
            )

        retryableJobs.mapNotNull { it.id }.forEach { failedJobId -> retryFailedJobById(failedJobId, now) }
    }

    private fun findManualRetryFailedJobIds(runId: UUID): List<UUID> {
        return transactionOperations.execute<List<UUID>> {
            if (!linkCrawlRunRepository.existsById(runId)) {
                throw ApiException(LinkStatus.LINK_CRAWL_RUN_NOT_FOUND)
            }

            linkCrawlFailedJobRepository
                .findAllByLastRunIdAndResolvedAtIsNullOrderByCreatedAtAsc(runId, LinkCrawlFailedJobRetryPolicy.pageRequest())
                .mapNotNull { it.id }
        } ?: emptyList()
    }

    private fun retryFailedJobById(
        failedJobId: UUID,
        automaticRetryAt: Instant? = null,
    ): Boolean {
        val retryContext = loadRetryContextIfRetryable(failedJobId, automaticRetryAt) ?: return false
        val snapshotResult = runCatching { resolveSnapshotForFailedJob(retryContext) }

        return applyRetryResult(failedJobId, automaticRetryAt, snapshotResult)
    }

    /**
     * 외부 페이지를 가져오기 전에 재시도할 값어치가 있는 잡인지 확인한다.
     * 목록을 뽑은 뒤 다른 경로가 먼저 해소했을 수 있어, 여기서 걸러내지 않으면 이미 끝난 URL을 한 번 더 크롤하게 된다.
     */
    private fun loadRetryContextIfRetryable(
        failedJobId: UUID,
        automaticRetryAt: Instant?,
    ): LinkFailedJobRetryContext? {
        return transactionOperations.execute<LinkFailedJobRetryContext?> {
            val failedJob = linkCrawlFailedJobRepository.findById(failedJobId).orElse(null) ?: return@execute null
            if (!isRetryable(failedJob, automaticRetryAt)) {
                return@execute null
            }

            LinkFailedJobRetryContext.from(failedJob)
        }
    }

    /**
     * 외부 fetch가 끝난 뒤 잡 상태를 다시 읽어 결과를 반영한다.
     * fetch 동안 상태가 바뀌었을 수 있으므로 저장 직전에 재시도 조건을 한 번 더 확인한다.
     */
    private fun applyRetryResult(
        failedJobId: UUID,
        automaticRetryAt: Instant?,
        snapshotResult: Result<LinkSnapshot>,
    ): Boolean {
        return transactionOperations.execute<Boolean> {
            val failedJob = linkCrawlFailedJobRepository.findById(failedJobId).orElse(null) ?: return@execute false
            if (!isRetryable(failedJob, automaticRetryAt)) {
                return@execute false
            }

            snapshotResult.fold(
                onSuccess = { snapshot -> retryFailedJobWithSnapshot(failedJob, snapshot) },
                onFailure = { exception ->
                    markRetryFailure(failedJob, exception)
                    false
                },
            )
        } ?: false
    }

    /**
     * 이미 해소된 잡은 경로와 무관하게 재시도 대상이 아니다.
     * 배치 활성 여부와 재시도 상한·backoff는 자동 재시도에만 적용된다. 관리자가 직접 누른 재시도는 그 조건을 넘어서 시도한다.
     */
    private fun isRetryable(
        failedJob: LinkCrawlFailedJob,
        automaticRetryAt: Instant?,
    ): Boolean {
        if (failedJob.resolvedAt != null) {
            return false
        }

        return automaticRetryAt == null || LinkCrawlFailedJobRetryPolicy.canRetryAutomatically(failedJob, automaticRetryAt)
    }

    private fun retryFailedJobWithSnapshot(
        failedJob: LinkCrawlFailedJob,
        snapshot: LinkSnapshot,
    ): Boolean {
        val batch = failedJob.batch
        val tagResolver = linkCrawlLinkCollector.tagResolverFor(batch)

        return try {
            linkCrawlLinkCollector.saveLinkAndResolveFailedJob(snapshot, batch, tagResolver)
            true
        } catch (exception: Exception) {
            markRetryFailure(failedJob, exception)
            false
        }
    }

    private fun markRetryFailure(
        failedJob: LinkCrawlFailedJob,
        exception: Throwable,
    ) {
        linkCrawlFailedJobRepository.recordRetryFailureIfUnresolved(
            failedJobId = requireNotNull(failedJob.id),
            errorStatusCode = exception.toLinkCrawlErrorStatusCode(),
            errorMessage = exception.toLinkCrawlErrorMessage(),
            failedAt = Instant.now(),
        )
    }

    private fun summarizeRetryRun(runId: UUID): LinkFailedJobRetrySummary {
        return transactionOperations.execute<LinkFailedJobRetrySummary> {
            val run = findRunOrThrow(runId)
            val stillUnresolvedCount = linkCrawlFailedJobRepository.countByLastRunIdAndResolvedAtIsNull(runId).toInt()
            LinkFailedJobRetrySummary(
                stillUnresolvedCount = stillUnresolvedCount,
                runStatus = run.currentStatus(hasUnresolvedFailedJobs = stillUnresolvedCount > 0),
            )
        } ?: throw ApiException(DefaultStatus.SERVER_ERROR, "실패 잡 재시도 결과를 요약하지 못했습니다")
    }

    private fun findRunOrThrow(runId: UUID): LinkCrawlRun {
        return linkCrawlRunRepository.findById(runId).orElseThrow {
            ApiException(LinkStatus.LINK_CRAWL_RUN_NOT_FOUND)
        }
    }

    private fun resolveSnapshotForFailedJob(retryContext: LinkFailedJobRetryContext): LinkSnapshot {
        return crawlDocumentParser.extractSnapshotFromArticlePage(retryContext.articleUrl, retryContext.selectors)
            ?: throw ApiException(LinkStatus.LINK_CRAWL_BATCH_NOT_CRAWLABLE)
    }

    private data class LinkFailedJobRetryContext(
        val articleUrl: String,
        val selectors: LinkCrawlSelectors,
    ) {
        companion object {
            fun from(failedJob: LinkCrawlFailedJob): LinkFailedJobRetryContext {
                return LinkFailedJobRetryContext(
                    articleUrl = failedJob.articleUrl,
                    selectors = LinkCrawlSelectors.from(failedJob.batch),
                )
            }
        }
    }

    private data class LinkFailedJobRetrySummary(
        val stillUnresolvedCount: Int,
        val runStatus: LinkCrawlRunStatus,
    )
}
