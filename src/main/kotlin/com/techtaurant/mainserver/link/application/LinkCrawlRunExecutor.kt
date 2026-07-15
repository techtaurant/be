package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.common.status.DefaultStatus
import com.techtaurant.mainserver.link.dto.LinkBatchRunResponse
import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import com.techtaurant.mainserver.link.entity.LinkCrawlFailedJob
import com.techtaurant.mainserver.link.entity.LinkCrawlRun
import com.techtaurant.mainserver.link.enums.LinkCrawlRunStatus
import com.techtaurant.mainserver.link.enums.LinkCrawlRunTriggerType
import com.techtaurant.mainserver.link.enums.LinkStatus
import com.techtaurant.mainserver.link.infrastructure.out.LinkCrawlFailedJobRepository
import com.techtaurant.mainserver.link.infrastructure.out.LinkCrawlRunRepository
import org.jsoup.nodes.Element
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations
import java.net.URI
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
            val crawlResult = crawl(run, tagResolver)
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
        linkCrawlRunRepository.save(run)
    }

    private fun crawl(
        run: LinkCrawlRun,
        tagResolver: LinkTagResolver,
    ): LinkCrawlResult {
        val batch = run.batch
        var crawlResponse = emptyCrawlResponse()
        val failedJobs = mutableListOf<LinkFailedJobRecord>()
        val crawledArticleUrls = mutableSetOf<String>()
        var page = batch.startPage

        while (run.triggerType != LinkCrawlRunTriggerType.CREATED || page <= batch.endPage) {
            val pageResult = crawlPage(batch, tagResolver, page, crawledArticleUrls)
            val stopCondition = stopConditionFor(batch, page, pageResult)
            if (stopCondition == CrawlStopCondition.BEFORE_MERGE) {
                break
            }

            crawlResponse = crawlResponse.mergePageResult(pageResult.response)
            failedJobs += pageResult.failedJobs
            if (stopCondition == CrawlStopCondition.AFTER_MERGE) {
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
        crawledArticleUrls: MutableSet<String>,
    ): LinkPageCrawlResult {
        val pageUrl = crawlDocumentParser.buildPageUrl(batch.baseUrl, batch.pageUriTemplate, page)
        val document =
            crawlDocumentParser.fetchPageOrNull(pageUrl)
                ?: if (page == batch.startPage) {
                    throw ApiException(LinkStatus.LINK_CRAWL_BATCH_NOT_CRAWLABLE)
                } else {
                    return emptyPageCrawlResult(hasNewUrls = false, isFetchFailed = true)
                }
        val items = document.select(batch.itemSelector)
        val pageArticleUrls =
            items.mapNotNull { item -> crawlDocumentParser.extractArticleUrl(item, batch, pageUrl) }
                .map(::normalizeUrlForComparison)
                .distinct()

        if (pageArticleUrls.isEmpty()) {
            return emptyPageCrawlResult(hasNewUrls = false)
        }

        val hasNewUrlInRun = pageArticleUrls.any { it !in crawledArticleUrls }
        if (!hasNewUrlInRun) {
            return emptyPageCrawlResult(hasNewUrls = false)
        }

        crawledArticleUrls += pageArticleUrls
        var pageResult = emptyPageCrawlResult(hasNewUrls = false)

        items.forEach { item ->
            val collectionResult = collectLinkFromCrawledItem(item, batch, tagResolver, pageUrl)
            pageResult = pageResult.recordCollectionResult(collectionResult)
        }

        return pageResult.copy(
            pageUrl = pageUrl,
            documentLocation = document.location(),
        )
    }

    private fun stopConditionFor(
        batch: LinkCrawlBatch,
        page: Int,
        pageResult: LinkPageCrawlResult,
    ): CrawlStopCondition? =
        when {
            pageResult.isFetchFailed -> CrawlStopCondition.BEFORE_MERGE
            page > batch.startPage && isRedirectedPage(pageResult.pageUrl, pageResult.documentLocation) -> CrawlStopCondition.BEFORE_MERGE
            !pageResult.hasNewUrls -> CrawlStopCondition.AFTER_MERGE
            else -> null
        }

    private fun emptyCrawlResponse(): LinkBatchRunResponse =
        LinkBatchRunResponse(
            collectedCount = 0,
            newLinkCount = 0,
            existingLinkCount = 0,
            skippedCount = 0,
        )

    private fun emptyPageCrawlResult(
        hasNewUrls: Boolean,
        isFetchFailed: Boolean = false,
    ): LinkPageCrawlResult =
        LinkPageCrawlResult(
            response = emptyCrawlResponse(),
            failedJobs = emptyList(),
            hasNewUrls = hasNewUrls,
            pageUrl = "",
            documentLocation = "",
            isFetchFailed = isFetchFailed,
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
            hasNewUrls = hasNewUrls || result.hasNewUrl(),
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
        linkCrawlRunRepository.save(run)
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

    private fun isRedirectedPage(
        pageUrl: String,
        documentLocation: String,
    ): Boolean {
        if (documentLocation.isBlank()) {
            return false
        }

        return normalizeUrlForComparison(pageUrl) != normalizeUrlForComparison(documentLocation)
    }

    private fun normalizeUrlForComparison(url: String): String {
        val trimmedUrl = url.trim()
        val uri =
            runCatching { URI.create(trimmedUrl).normalize() }.getOrNull()
                ?: return trimmedUrl.trimEnd('/')
        return runCatching {
            val normalizedPath = uri.path?.trimEnd('/')?.takeIf(String::isNotBlank)
            URI(
                uri.scheme?.lowercase(),
                uri.userInfo,
                uri.host?.lowercase(),
                uri.port,
                normalizedPath,
                uri.query,
                null,
            ).toString().trimEnd('/')
        }.getOrElse {
            trimmedUrl.trimEnd('/')
        }
    }

    private fun LinkCollectionResult.hasNewUrl(): Boolean =
        when (this) {
            LinkCollectionResult.CreatedNewLink,
            LinkCollectionResult.ConnectedExistingLink,
            is LinkCollectionResult.Failed,
            -> true
            LinkCollectionResult.UpdatedExistingLink,
            LinkCollectionResult.Skipped,
            -> false
        }

    private data class LinkCrawlResult(
        val response: LinkBatchRunResponse,
        val failedJobs: List<LinkFailedJobRecord>,
    )

    private data class LinkPageCrawlResult(
        val response: LinkBatchRunResponse,
        val failedJobs: List<LinkFailedJobRecord>,
        val hasNewUrls: Boolean,
        val pageUrl: String,
        val documentLocation: String,
        val isFetchFailed: Boolean,
    )

    private enum class CrawlStopCondition {
        BEFORE_MERGE,
        AFTER_MERGE,
    }

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
