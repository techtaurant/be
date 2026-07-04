package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.common.status.DefaultStatus
import com.techtaurant.mainserver.link.dto.LinkBatchRunResponse
import com.techtaurant.mainserver.link.dto.LinkCrawlFailedJobResponse
import com.techtaurant.mainserver.link.dto.LinkCrawlFailedJobRetryResponse
import com.techtaurant.mainserver.link.dto.LinkCrawlRunResponse
import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import com.techtaurant.mainserver.link.entity.LinkCrawlRun
import com.techtaurant.mainserver.link.enums.LinkCrawlRunStatus
import com.techtaurant.mainserver.link.enums.LinkCrawlRunTriggerType
import com.techtaurant.mainserver.link.enums.LinkStatus
import com.techtaurant.mainserver.link.infrastructure.out.LinkCrawlBatchRepository
import com.techtaurant.mainserver.link.infrastructure.out.LinkCrawlFailedJobRepository
import com.techtaurant.mainserver.link.infrastructure.out.LinkCrawlRunRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionOperations
import java.time.Instant
import java.util.UUID

@Service
class LinkBatchRunService(
    private val linkCrawlBatchRepository: LinkCrawlBatchRepository,
    private val linkCrawlRunRepository: LinkCrawlRunRepository,
    private val linkCrawlFailedJobRepository: LinkCrawlFailedJobRepository,
    private val linkCrawlRunExecutor: LinkCrawlRunExecutor,
    private val linkCrawlFailedJobRetryService: LinkCrawlFailedJobRetryService,
    private val linkDocumentFetcher: LinkDocumentFetcher,
    private val transactionOperations: TransactionOperations,
) {
    private val crawlDocumentParser = LinkCrawlDocumentParser(linkDocumentFetcher)

    fun run(
        batchId: UUID,
        triggerType: LinkCrawlRunTriggerType = LinkCrawlRunTriggerType.MANUAL,
    ): LinkBatchRunResponse {
        val runId = createRun(batchId, triggerType)
        return try {
            linkCrawlRunExecutor.execute(runId)
        } catch (exception: Exception) {
            val apiException = exception.toApiException()
            transactionOperations.execute<Unit> {
                linkCrawlRunExecutor.markRunFailed(runId, apiException)
            }
            throw apiException
        }
    }

    @Transactional(readOnly = true)
    fun getRuns(batchId: UUID): List<LinkCrawlRunResponse> {
        if (!linkCrawlBatchRepository.existsById(batchId)) {
            throw ApiException(LinkStatus.LINK_CRAWL_BATCH_NOT_FOUND)
        }

        val runs = linkCrawlRunRepository.findAllByBatchIdOrderByStartedAtDesc(batchId)
        if (runs.isEmpty()) {
            return emptyList()
        }

        val runIdsWithUnresolved = linkCrawlFailedJobRepository.findRunIdsWithUnresolvedJobs(runs.mapNotNull { it.id })
        return runs.map { run -> LinkCrawlRunResponse.from(run, run.id in runIdsWithUnresolved) }
    }

    @Transactional(readOnly = true)
    fun getUnresolvedFailedJobs(runId: UUID): List<LinkCrawlFailedJobResponse> {
        if (!linkCrawlRunRepository.existsById(runId)) {
            throw ApiException(LinkStatus.LINK_CRAWL_RUN_NOT_FOUND)
        }

        return linkCrawlFailedJobRepository.findAllByRunIdAndResolvedFalseOrderByCreatedAtAsc(runId)
            .map(LinkCrawlFailedJobResponse::from)
    }

    fun retryRunFailedJobs(runId: UUID): LinkCrawlFailedJobRetryResponse {
        return linkCrawlFailedJobRetryService.retryRunFailedJobs(runId)
    }

    fun retryAllUnresolvedFailedJobs(now: Instant = Instant.now()) {
        linkCrawlFailedJobRetryService.retryAllUnresolvedFailedJobs(now)
    }

    fun validateCrawlable(batch: LinkCrawlBatch) {
        val pageUrl = crawlDocumentParser.buildPageUrl(batch.baseUrl, batch.pageUriTemplate, batch.startPage)
        val document = crawlDocumentParser.fetchPageOrNull(pageUrl) ?: throw ApiException(LinkStatus.LINK_CRAWL_BATCH_NOT_CRAWLABLE)
        val hasCrawlableItem =
            document.select(batch.itemSelector)
                .any { item -> crawlDocumentParser.extractSnapshot(item, batch, pageUrl) != null }

        if (!hasCrawlableItem) {
            throw ApiException(LinkStatus.LINK_CRAWL_BATCH_NOT_CRAWLABLE)
        }
    }

    private fun createRun(
        batchId: UUID,
        triggerType: LinkCrawlRunTriggerType,
    ): UUID {
        return transactionOperations.execute<UUID> {
            val batch =
                linkCrawlBatchRepository.findById(batchId).orElseThrow {
                    ApiException(LinkStatus.LINK_CRAWL_BATCH_NOT_FOUND)
                }

            val startedAt = Instant.now()
            linkCrawlRunRepository.save(
                LinkCrawlRun(
                    batch = batch,
                    triggerType = triggerType,
                    status = LinkCrawlRunStatus.COMPLETED,
                    startedAt = startedAt,
                    finishedAt = startedAt,
                ),
            ).id ?: throw ApiException(DefaultStatus.SERVER_ERROR, "실행 ID가 없습니다")
        } ?: throw ApiException(DefaultStatus.SERVER_ERROR, "실행 이력을 생성하지 못했습니다")
    }
}
