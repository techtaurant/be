package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.common.status.DefaultStatus
import com.techtaurant.mainserver.link.dto.LinkBatchRunResponse
import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import com.techtaurant.mainserver.link.entity.LinkCrawlFailedJob
import com.techtaurant.mainserver.link.entity.LinkCrawlRun
import com.techtaurant.mainserver.link.enums.LinkCrawlRunStatus
import com.techtaurant.mainserver.link.enums.LinkStatus
import com.techtaurant.mainserver.link.infrastructure.out.LinkCrawlFailedJobRepository
import com.techtaurant.mainserver.link.infrastructure.out.LinkCrawlRunRepository
import org.jsoup.nodes.Element
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations
import java.time.Instant
import java.util.UUID

@Service
class LinkCrawlRunExecutor(
    private val linkCrawlRunRepository: LinkCrawlRunRepository,
    private val linkCrawlFailedJobRepository: LinkCrawlFailedJobRepository,
    private val linkCrawlLinkCollector: LinkCrawlLinkCollector,
    private val linkDocumentFetcher: LinkDocumentFetcher,
    private val transactionOperations: TransactionOperations,
) {
    private val crawlDocumentParser = LinkCrawlDocumentParser(linkDocumentFetcher)

    internal fun execute(runId: UUID): LinkBatchRunResponse {
        return transactionOperations.execute<LinkBatchRunResponse> {
            val run = findRunOrThrow(runId)
            val batch = run.batch
            val tagResolver = linkCrawlLinkCollector.tagResolverFor(batch)
            val crawlResult = crawl(batch, tagResolver)
            crawlResult.failedJobs.forEach { failedJob -> recordFailedJob(run, failedJob) }
            val result = crawlResult.response

            completeRun(run, result)
            result
        } ?: throw ApiException(DefaultStatus.SERVER_ERROR, "크롤 실행 결과가 없습니다")
    }

    internal fun markRunFailed(
        runId: UUID,
        exception: Throwable,
    ) {
        val run = linkCrawlRunRepository.findById(runId).orElse(null) ?: return
        run.status = LinkCrawlRunStatus.FAILED
        run.errorStatusCode = exception.toLinkCrawlErrorStatusCode()
        run.errorMessage = exception.toLinkCrawlErrorMessage()
        run.finishedAt = Instant.now()
        run.batch.lastTriggeredAt = Instant.now()
    }

    private fun crawl(
        batch: LinkCrawlBatch,
        tagResolver: LinkTagResolver,
    ): LinkCrawlResult {
        var crawlResponse = emptyCrawlResponse()
        val failedJobs = mutableListOf<LinkFailedJobRecord>()
        var page = batch.startPage

        while (true) {
            val pageResult = crawlPage(batch, tagResolver, page) ?: break
            crawlResponse = crawlResponse.mergePageResult(pageResult.response)
            failedJobs += pageResult.failedJobs

            if (!pageResult.hasItems) {
                break
            }
            page++
        }

        return LinkCrawlResult(
            response = crawlResponse,
            failedJobs = failedJobs,
        )
    }

    private fun crawlPage(
        batch: LinkCrawlBatch,
        tagResolver: LinkTagResolver,
        page: Int,
    ): LinkPageCrawlResult? {
        val pageUrl = crawlDocumentParser.buildPageUrl(batch.baseUrl, batch.pageUriTemplate, page)
        val document =
            crawlDocumentParser.fetchPageOrNull(pageUrl)
                ?: if (page == batch.startPage) {
                    throw ApiException(LinkStatus.LINK_CRAWL_BATCH_NOT_CRAWLABLE)
                } else {
                    return null
                }
        val items = document.select(batch.itemSelector)
        var pageResult = emptyPageCrawlResult(hasItems = items.isNotEmpty())

        items.forEach { item ->
            val collectionResult = collectLinkFromCrawledItem(item, batch, tagResolver, pageUrl)
            pageResult = pageResult.recordCollectionResult(collectionResult)
        }

        return pageResult
    }

    private fun emptyCrawlResponse(): LinkBatchRunResponse =
        LinkBatchRunResponse(
            collectedCount = 0,
            newLinkCount = 0,
            existingLinkCount = 0,
            skippedCount = 0,
        )

    private fun emptyPageCrawlResult(hasItems: Boolean): LinkPageCrawlResult =
        LinkPageCrawlResult(
            response = emptyCrawlResponse(),
            failedJobs = emptyList(),
            hasItems = hasItems,
        )

    private fun LinkPageCrawlResult.recordCollectionResult(result: LinkCollectionResult): LinkPageCrawlResult {
        val updatedResponse =
            when (result) {
                LinkCollectionResult.CreatedNewLink ->
                    response.copy(
                        collectedCount = response.collectedCount + 1,
                        newLinkCount = response.newLinkCount + 1,
                    )
                LinkCollectionResult.ConnectedExistingLink,
                LinkCollectionResult.UpdatedExistingLink,
                ->
                    response.copy(
                        collectedCount = response.collectedCount + 1,
                        existingLinkCount = response.existingLinkCount + 1,
                    )
                LinkCollectionResult.Skipped ->
                    response.copy(skippedCount = response.skippedCount + 1)
                is LinkCollectionResult.Failed ->
                    response.copy(failedJobCount = response.failedJobCount + 1)
            }

        return copy(
            response = updatedResponse,
            failedJobs =
                if (result is LinkCollectionResult.Failed) {
                    failedJobs + result.failedJob
                } else {
                    failedJobs
                },
        )
    }

    private fun LinkBatchRunResponse.mergePageResult(pageResult: LinkBatchRunResponse): LinkBatchRunResponse =
        copy(
            collectedCount = collectedCount + pageResult.collectedCount,
            newLinkCount = newLinkCount + pageResult.newLinkCount,
            existingLinkCount = existingLinkCount + pageResult.existingLinkCount,
            skippedCount = skippedCount + pageResult.skippedCount,
            failedJobCount = failedJobCount + pageResult.failedJobCount,
        )

    private fun collectLinkFromCrawledItem(
        item: Element,
        batch: LinkCrawlBatch,
        tagResolver: LinkTagResolver,
        pageUrl: String,
    ): LinkCollectionResult {
        val snapshot =
            try {
                crawlDocumentParser.extractSnapshot(item, batch, pageUrl) ?: return LinkCollectionResult.Skipped
            } catch (exception: Exception) {
                val failedJobDraft = crawlDocumentParser.extractFailedJobDraft(item, batch, pageUrl) ?: return LinkCollectionResult.Skipped
                return LinkCollectionResult.Failed(
                    LinkFailedJobRecord(
                        draft = failedJobDraft,
                        exception = exception,
                    ),
                )
            }

        return try {
            when (linkCrawlLinkCollector.collect(snapshot, batch, tagResolver)) {
                LinkCrawlLinkCollectResult.CREATED_NEW_LINK -> LinkCollectionResult.CreatedNewLink
                LinkCrawlLinkCollectResult.CONNECTED_EXISTING_LINK -> LinkCollectionResult.ConnectedExistingLink
                LinkCrawlLinkCollectResult.UPDATED_EXISTING_LINK -> LinkCollectionResult.UpdatedExistingLink
            }
        } catch (exception: Exception) {
            LinkCollectionResult.Failed(
                LinkFailedJobRecord(
                    draft = snapshot.toFailedJobDraft(),
                    exception = exception,
                ),
            )
        }
    }

    private fun completeRun(
        run: LinkCrawlRun,
        result: LinkBatchRunResponse,
    ) {
        run.collectedCount = result.collectedCount
        run.newLinkCount = result.newLinkCount
        run.existingLinkCount = result.existingLinkCount
        run.skippedCount = result.skippedCount
        run.failedJobCount = result.failedJobCount
        run.errorStatusCode = null
        run.errorMessage = null
        run.finishedAt = Instant.now()
        run.status =
            if (result.failedJobCount == 0) LinkCrawlRunStatus.COMPLETED else LinkCrawlRunStatus.UNRESOLVED
        run.batch.lastTriggeredAt = Instant.now()
    }

    private fun findRunOrThrow(runId: UUID): LinkCrawlRun {
        return linkCrawlRunRepository.findById(runId).orElseThrow {
            ApiException(LinkStatus.LINK_CRAWL_RUN_NOT_FOUND)
        }
    }

    private fun recordFailedJob(
        run: LinkCrawlRun,
        failedJobRecord: LinkFailedJobRecord,
    ) {
        val runId = run.id ?: throw ApiException(DefaultStatus.SERVER_ERROR, "실행 ID가 없습니다")
        val now = Instant.now()
        val failedJobDraft = failedJobRecord.draft.toPersistableFailedJobDraft()
        val errorStatusCode = failedJobRecord.exception.toLinkCrawlErrorStatusCode()
        val errorMessage = failedJobRecord.exception.toLinkCrawlErrorMessage()
        val failedJob =
            linkCrawlFailedJobRepository.findByRunIdAndArticleUrl(runId, failedJobDraft.articleUrl)
                ?.apply {
                    this.errorStatusCode = errorStatusCode
                    this.errorMessage = errorMessage
                    this.failureCount += 1
                    this.lastFailedAt = now
                }
                ?: LinkCrawlFailedJob(
                    run = run,
                    articleUrl = failedJobDraft.articleUrl,
                    errorStatusCode = errorStatusCode,
                    errorMessage = errorMessage,
                    lastFailedAt = now,
                )

        linkCrawlFailedJobRepository.save(failedJob)
    }

    private data class LinkCrawlResult(
        val response: LinkBatchRunResponse,
        val failedJobs: List<LinkFailedJobRecord>,
    )

    private data class LinkPageCrawlResult(
        val response: LinkBatchRunResponse,
        val failedJobs: List<LinkFailedJobRecord>,
        val hasItems: Boolean,
    )

    private sealed class LinkCollectionResult {
        data object CreatedNewLink : LinkCollectionResult()

        data object ConnectedExistingLink : LinkCollectionResult()

        data object UpdatedExistingLink : LinkCollectionResult()

        data object Skipped : LinkCollectionResult()

        data class Failed(
            val failedJob: LinkFailedJobRecord,
        ) : LinkCollectionResult()
    }
}
