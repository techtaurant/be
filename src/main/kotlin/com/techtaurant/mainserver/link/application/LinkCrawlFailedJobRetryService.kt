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
                .findAllByRunIdAndResolvedAtIsNullOrderByCreatedAtAsc(runId, LinkCrawlFailedJobRetryPolicy.pageRequest())
                .mapNotNull { it.id }
        } ?: emptyList()
    }

    private fun retryFailedJobById(
        failedJobId: UUID,
        automaticRetryAt: Instant? = null,
    ): Boolean {
        val retryContext =
            transactionOperations.execute<LinkFailedJobRetryContext?> {
                val failedJob = linkCrawlFailedJobRepository.findById(failedJobId).orElse(null) ?: return@execute null
                if (automaticRetryAt != null && !LinkCrawlFailedJobRetryPolicy.canRetryAutomatically(failedJob, automaticRetryAt)) {
                    return@execute null
                }

                LinkFailedJobRetryContext.from(failedJob)
            } ?: return false

        val snapshotResult = runCatching { resolveSnapshotForFailedJob(retryContext) }
        return transactionOperations.execute<Boolean> {
            val failedJob = linkCrawlFailedJobRepository.findById(failedJobId).orElse(null) ?: return@execute false
            if (failedJob.resolvedAt != null) {
                refreshRunStatus(failedJob.run)
                return@execute false
            }
            if (automaticRetryAt != null && !LinkCrawlFailedJobRetryPolicy.canRetryAutomatically(failedJob, automaticRetryAt)) {
                return@execute false
            }

            val succeeded =
                snapshotResult.fold(
                    onSuccess = { snapshot -> retryFailedJobWithSnapshot(failedJob, snapshot) },
                    onFailure = { exception ->
                        markRetryFailure(failedJob, exception)
                        false
                    },
                )
            refreshRunStatus(failedJob.run)
            succeeded
        } ?: false
    }

    private fun retryFailedJobWithSnapshot(
        failedJob: LinkCrawlFailedJob,
        snapshot: LinkSnapshot,
    ): Boolean {
        val batch = failedJob.run.batch
        val tagResolver = linkCrawlLinkCollector.tagResolverFor(batch)

        return try {
            linkCrawlLinkCollector.collect(snapshot, batch, tagResolver)
            markResolved(failedJob)
            true
        } catch (exception: Exception) {
            markRetryFailure(failedJob, exception)
            false
        }
    }

    private fun markResolved(failedJob: LinkCrawlFailedJob) {
        failedJob.resolvedAt = Instant.now()
        linkCrawlFailedJobRepository.save(failedJob)
    }

    private fun markRetryFailure(
        failedJob: LinkCrawlFailedJob,
        exception: Throwable,
    ) {
        failedJob.failureCount += 1
        failedJob.errorStatusCode = exception.toLinkCrawlErrorStatusCode()
        failedJob.errorMessage = exception.toLinkCrawlErrorMessage()
        failedJob.lastFailedAt = Instant.now()
        linkCrawlFailedJobRepository.save(failedJob)
    }

    private fun refreshRunStatus(run: LinkCrawlRun) {
        val runId = run.id ?: return
        run.status =
            when {
                run.status == LinkCrawlRunStatus.FAILED -> LinkCrawlRunStatus.FAILED
                linkCrawlFailedJobRepository.existsByRunIdAndResolvedAtIsNull(runId) -> LinkCrawlRunStatus.UNRESOLVED
                run.failedJobCount > 0 -> LinkCrawlRunStatus.RESOLVED
                else -> LinkCrawlRunStatus.COMPLETED
            }
        linkCrawlRunRepository.save(run)
    }

    private fun summarizeRetryRun(runId: UUID): LinkFailedJobRetrySummary {
        return transactionOperations.execute<LinkFailedJobRetrySummary> {
            val run = findRunOrThrow(runId)
            refreshRunStatus(run)
            LinkFailedJobRetrySummary(
                stillUnresolvedCount = linkCrawlFailedJobRepository.countByRunIdAndResolvedAtIsNull(runId).toInt(),
                runStatus = run.status,
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
                    selectors = LinkCrawlSelectors.from(failedJob.run.batch),
                )
            }
        }
    }

    private data class LinkFailedJobRetrySummary(
        val stillUnresolvedCount: Int,
        val runStatus: LinkCrawlRunStatus,
    )
}
