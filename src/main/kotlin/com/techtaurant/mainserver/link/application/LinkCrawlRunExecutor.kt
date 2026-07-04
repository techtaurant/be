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
        val seenFailedArticleUrls = mutableSetOf<String>()
        var page = batch.startPage

        while (true) {
            val pageResult = crawlPage(batch, tagResolver, page, seenFailedArticleUrls) ?: break
            crawlResponse = crawlResponse.mergePageResult(pageResult.response)
            failedJobs += pageResult.failedJobs

            if (!pageResult.hasProgress) {
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
        seenFailedArticleUrls: MutableSet<String>,
    ): LinkPageCrawlResult? {
        val pageUrl = crawlDocumentParser.buildPageUrl(batch.baseUrl, batch.pageUriTemplate, page)
        val document =
            crawlDocumentParser.fetchPageOrNull(pageUrl)
                ?: if (page == batch.startPage) {
                    throw ApiException(LinkStatus.LINK_CRAWL_BATCH_NOT_CRAWLABLE)
                } else {
                    return null
                }
        var pageResult = emptyPageCrawlResult()

        document.select(batch.itemSelector).forEach { item ->
            val collectionResult = collectLinkFromCrawledItem(item, batch, tagResolver, page, pageUrl)
            pageResult = pageResult.recordCollectionResult(collectionResult, seenFailedArticleUrls)
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

    private fun emptyPageCrawlResult(): LinkPageCrawlResult =
        LinkPageCrawlResult(
            response = emptyCrawlResponse(),
            failedJobs = emptyList(),
            hasProgress = false,
        )

    private fun LinkPageCrawlResult.recordCollectionResult(
        result: LinkCollectionResult,
        seenFailedArticleUrls: MutableSet<String>,
    ): LinkPageCrawlResult {
        val hasNewFailedArticleUrl =
            result is LinkCollectionResult.Failed &&
                seenFailedArticleUrls.add(result.failedJob.draft.articleUrl)
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
            hasProgress = hasProgress || result.hasProgress || hasNewFailedArticleUrl,
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
        page: Int,
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
                        sourcePage = page,
                        sourcePageUrl = pageUrl,
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
                    sourcePage = page,
                    sourcePageUrl = pageUrl,
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
        val sourcePageUrl = LinkCrawlFailedJob.truncateUrl(failedJobRecord.sourcePageUrl)
        val errorStatusCode = failedJobRecord.exception.toLinkCrawlErrorStatusCode()
        val errorMessage = failedJobRecord.exception.toLinkCrawlErrorMessage()
        val failedJob =
            linkCrawlFailedJobRepository.findByRunIdAndArticleUrl(runId, failedJobDraft.articleUrl)
                ?.apply {
                    this.sourcePage = failedJobRecord.sourcePage
                    this.sourcePageUrl = sourcePageUrl
                    this.title = failedJobDraft.title
                    this.summary = failedJobDraft.summary
                    this.errorStatusCode = errorStatusCode
                    this.errorMessage = errorMessage
                    this.failureCount += 1
                    this.lastFailedAt = now
                }
                ?: LinkCrawlFailedJob(
                    run = run,
                    sourcePage = failedJobRecord.sourcePage,
                    sourcePageUrl = sourcePageUrl,
                    articleUrl = failedJobDraft.articleUrl,
                    title = failedJobDraft.title,
                    summary = failedJobDraft.summary,
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
        val hasProgress: Boolean,
    )

    private sealed class LinkCollectionResult(
        val hasProgress: Boolean,
    ) {
        data object CreatedNewLink : LinkCollectionResult(true)

        data object ConnectedExistingLink : LinkCollectionResult(true)

        data object UpdatedExistingLink : LinkCollectionResult(false)

        data object Skipped : LinkCollectionResult(false)

        data class Failed(
            val failedJob: LinkFailedJobRecord,
        ) : LinkCollectionResult(false)
    }
}
