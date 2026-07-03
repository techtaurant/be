package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.common.status.DefaultStatus
import com.techtaurant.mainserver.link.dto.LinkBatchRunResponse
import com.techtaurant.mainserver.link.dto.LinkCrawlFailedJobResponse
import com.techtaurant.mainserver.link.dto.LinkCrawlFailedJobRetryResponse
import com.techtaurant.mainserver.link.dto.LinkCrawlRunResponse
import com.techtaurant.mainserver.link.entity.Link
import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import com.techtaurant.mainserver.link.entity.LinkCrawlFailedJob
import com.techtaurant.mainserver.link.entity.LinkCrawlRun
import com.techtaurant.mainserver.link.entity.UserLink
import com.techtaurant.mainserver.link.enums.LinkCrawlRunStatus
import com.techtaurant.mainserver.link.enums.LinkCrawlRunTriggerType
import com.techtaurant.mainserver.link.enums.LinkStatus
import com.techtaurant.mainserver.link.infrastructure.out.LinkCrawlBatchRepository
import com.techtaurant.mainserver.link.infrastructure.out.LinkCrawlFailedJobRepository
import com.techtaurant.mainserver.link.infrastructure.out.LinkCrawlRunRepository
import com.techtaurant.mainserver.link.infrastructure.out.LinkRepository
import com.techtaurant.mainserver.link.infrastructure.out.UserLinkRepository
import com.techtaurant.mainserver.post.application.TagWriteService
import com.techtaurant.mainserver.post.entity.Tag
import com.techtaurant.mainserver.user.entity.User
import org.jsoup.nodes.Element
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
    private val linkRepository: LinkRepository,
    private val userLinkRepository: UserLinkRepository,
    private val tagWriteService: TagWriteService,
    private val linkDocumentFetcher: LinkDocumentFetcher,
    private val transactionOperations: TransactionOperations,
) {
    private val crawlDocumentParser = LinkCrawlDocumentParser(linkDocumentFetcher)

    @Transactional
    fun run(
        batchId: UUID,
        triggerType: LinkCrawlRunTriggerType = LinkCrawlRunTriggerType.MANUAL,
    ): LinkBatchRunResponse {
        val batch =
            linkCrawlBatchRepository.findById(batchId).orElseThrow {
                ApiException(LinkStatus.LINK_CRAWL_BATCH_NOT_FOUND)
            }

        val startedAt = Instant.now()
        val run =
            linkCrawlRunRepository.save(
                LinkCrawlRun(
                    batch = batch,
                    triggerType = triggerType,
                    status = LinkCrawlRunStatus.COMPLETED,
                    startedAt = startedAt,
                    finishedAt = startedAt,
                ),
            )

        val tagResolver = LinkTagResolver(resolveLinkTagNames(batch.tagNames), tagWriteService::resolveTags)
        val crawlResult = crawl(batch, tagResolver)
        crawlResult.failedJobs.forEach { failedJob -> recordFailedJob(run, failedJob) }
        val result = crawlResult.response

        run.collectedCount = result.collectedCount
        run.newLinkCount = result.newLinkCount
        run.existingLinkCount = result.existingLinkCount
        run.skippedCount = result.skippedCount
        run.failedJobCount = result.failedJobCount
        run.finishedAt = Instant.now()
        run.status =
            if (result.failedJobCount == 0) LinkCrawlRunStatus.COMPLETED else LinkCrawlRunStatus.UNRESOLVED
        batch.lastTriggeredAt = Instant.now()

        return result
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

    @Transactional
    fun retryRunFailedJobs(runId: UUID): LinkCrawlFailedJobRetryResponse {
        val run =
            linkCrawlRunRepository.findById(runId).orElseThrow {
                ApiException(LinkStatus.LINK_CRAWL_RUN_NOT_FOUND)
            }

        val unresolvedJobs = linkCrawlFailedJobRepository.findAllByRunIdAndResolvedFalseOrderByCreatedAtAsc(runId)
        val resolvedCount = unresolvedJobs.count { retryFailedJob(it) }
        refreshRunStatus(run)

        return LinkCrawlFailedJobRetryResponse(
            retriedCount = unresolvedJobs.size,
            resolvedCount = resolvedCount,
            stillUnresolvedCount = unresolvedJobs.size - resolvedCount,
            runStatus = run.status,
        )
    }

    fun retryAllUnresolvedFailedJobs(now: Instant = Instant.now()) {
        val retryableJobs =
            linkCrawlFailedJobRepository.findRetryableAutomaticJobs(
                maxFailureCount = LinkCrawlFailedJobRetryPolicy.MAX_FAILURE_COUNT,
                retryableBefore = LinkCrawlFailedJobRetryPolicy.retryableBefore(now),
                pageable = LinkCrawlFailedJobRetryPolicy.pageRequest(),
            )

        retryableJobs.mapNotNull { it.id }.forEach { failedJobId ->
            transactionOperations.execute<Unit> {
                retryAutomaticFailedJob(failedJobId, now)
            }
        }
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
        val document = crawlDocumentParser.fetchPageOrNull(pageUrl) ?: return null
        var pageResult = emptyPageCrawlResult()

        document.select(batch.itemSelector).forEach { item ->
            val collectionResult = collectLinkFromCrawledItem(item, batch, tagResolver, page, pageUrl)
            val hasNewFailedArticleUrl =
                collectionResult is LinkCollectionResult.Failed &&
                    seenFailedArticleUrls.add(collectionResult.failedJob.draft.articleUrl)
            pageResult = pageResult.recordCollectionResult(collectionResult, hasNewFailedArticleUrl)
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
        hasNewFailedArticleUrl: Boolean,
    ): LinkPageCrawlResult {
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

        return runCatching {
            saveNewLinkOrRefreshExistingLink(snapshot, batch, tagResolver)
        }.getOrElse { exception ->
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

    private fun saveNewLinkOrRefreshExistingLink(
        snapshot: LinkSnapshot,
        batch: LinkCrawlBatch,
        tagResolver: LinkTagResolver,
    ): LinkCollectionResult {
        val existingLink = linkRepository.findByUrl(snapshot.url)
        if (existingLink == null) {
            val savedLink = saveNewLink(snapshot, tagResolver.resolve())
            connectUserToLink(batch.companyUser, savedLink)
            return LinkCollectionResult.CreatedNewLink
        }

        refreshExistingLink(existingLink, snapshot)
        val isConnected = connectUserToLink(batch.companyUser, existingLink)
        return if (isConnected) {
            LinkCollectionResult.ConnectedExistingLink
        } else {
            LinkCollectionResult.UpdatedExistingLink
        }
    }

    private fun saveNewLink(
        snapshot: LinkSnapshot,
        tags: Set<Tag>,
    ): Link {
        return linkRepository.save(
            Link(
                title = snapshot.title,
                url = snapshot.url,
                summary = snapshot.summary,
                createdAt = snapshot.createdAt,
            ).apply {
                replaceTags(tags)
            },
        ).also { savedLink ->
            savedLink.createdAt = snapshot.createdAt
        }
    }

    private fun refreshExistingLink(
        existingLink: Link,
        snapshot: LinkSnapshot,
    ) {
        existingLink.title = snapshot.title
        if (snapshot.summary.isNotBlank()) {
            existingLink.summary = snapshot.summary
        }
        existingLink.createdAt = snapshot.createdAt
    }

    private fun connectUserToLink(
        user: User,
        link: Link,
    ): Boolean {
        val userId = user.id!!
        val linkId = link.id!!

        if (userLinkRepository.findByUserIdAndLinkId(userId, linkId) == null) {
            userLinkRepository.save(UserLink(user = user, link = link))
            return true
        }

        return false
    }

    private fun retryFailedJob(failedJob: LinkCrawlFailedJob): Boolean {
        val batch = failedJob.run.batch
        val tagResolver = LinkTagResolver(resolveLinkTagNames(batch.tagNames), tagWriteService::resolveTags)

        return runCatching {
            val snapshot = resolveSnapshotForFailedJob(failedJob)
            saveNewLinkOrRefreshExistingLink(snapshot, batch, tagResolver)
        }.map {
            markResolved(failedJob)
            true
        }.getOrElse { exception ->
            markRetryFailure(failedJob, exception)
            false
        }
    }

    private fun markResolved(failedJob: LinkCrawlFailedJob) {
        failedJob.resolved = true
        failedJob.resolvedAt = Instant.now()
        linkCrawlFailedJobRepository.save(failedJob)
    }

    private fun markRetryFailure(
        failedJob: LinkCrawlFailedJob,
        exception: Throwable,
    ) {
        failedJob.failureCount += 1
        failedJob.errorStatusCode = exception.toErrorStatusCode()
        failedJob.errorMessage = exception.toErrorMessage()
        failedJob.lastFailedAt = Instant.now()
        linkCrawlFailedJobRepository.save(failedJob)
    }

    private fun retryAutomaticFailedJob(
        failedJobId: UUID,
        now: Instant,
    ) {
        val failedJob = linkCrawlFailedJobRepository.findById(failedJobId).orElse(null) ?: return
        if (!LinkCrawlFailedJobRetryPolicy.canRetryAutomatically(failedJob, now)) {
            return
        }

        retryFailedJob(failedJob)
        refreshRunStatus(failedJob.run)
    }

    private fun refreshRunStatus(run: LinkCrawlRun) {
        val runId = run.id ?: return
        run.status =
            when {
                linkCrawlFailedJobRepository.existsByRunIdAndResolvedFalse(runId) -> LinkCrawlRunStatus.UNRESOLVED
                run.failedJobCount > 0 -> LinkCrawlRunStatus.RESOLVED
                else -> LinkCrawlRunStatus.COMPLETED
            }
    }

    private fun resolveSnapshotForFailedJob(failedJob: LinkCrawlFailedJob): LinkSnapshot {
        val batch = failedJob.run.batch
        val snapshotFromSourcePage =
            crawlDocumentParser.fetchPageOrNull(failedJob.sourcePageUrl)
                ?.select(batch.itemSelector)
                ?.firstNotNullOfOrNull { item ->
                    val articleUrl = crawlDocumentParser.extractArticleUrl(item, batch, failedJob.sourcePageUrl)
                    if (articleUrl == failedJob.articleUrl) {
                        crawlDocumentParser.extractSnapshot(item, batch, failedJob.sourcePageUrl)
                    } else {
                        null
                    }
                }

        if (snapshotFromSourcePage != null) {
            return snapshotFromSourcePage
        }

        val title =
            failedJob.title?.trim()?.takeIf(String::isNotEmpty)
                ?: throw ApiException(LinkStatus.LINK_CRAWL_BATCH_NOT_CRAWLABLE)
        val createdAt =
            crawlDocumentParser.parseCreatedAtFromArticlePage(failedJob.articleUrl, batch.createdAtSelectors)
                ?: throw ApiException(LinkStatus.LINK_CRAWL_BATCH_CREATED_AT_REQUIRED)

        return LinkSnapshot(
            title = title,
            url = failedJob.articleUrl,
            summary = failedJob.summary.orEmpty(),
            createdAt = createdAt,
        )
    }

    private fun recordFailedJob(
        run: LinkCrawlRun,
        failedJobRecord: LinkFailedJobRecord,
    ) {
        val runId = run.id ?: throw IllegalStateException("실행 ID가 없습니다")
        val now = Instant.now()
        val failedJobDraft = failedJobRecord.draft.toPersistableFailedJobDraft()
        val sourcePageUrl = LinkCrawlFailedJob.truncateUrl(failedJobRecord.sourcePageUrl)
        val errorStatusCode = failedJobRecord.exception.toErrorStatusCode()
        val errorMessage = failedJobRecord.exception.toErrorMessage()
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

    private fun Throwable.toErrorStatusCode(): Int {
        return if (this is ApiException) {
            status.getCustomStatusCode()
        } else {
            DefaultStatus.UNKNOWN_EXCEPTION.getCustomStatusCode()
        }
    }

    private fun Throwable.toErrorMessage(): String {
        return if (this is ApiException) {
            detail
        } else {
            message ?: javaClass.simpleName
        }
    }

    private fun resolveLinkTagNames(rawTagNames: String?): List<String> =
        rawTagNames.toLineList()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

    private fun String?.toLineList(): List<String> {
        return this?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toList()
            ?: emptyList()
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

    private class LinkTagResolver(
        private val tagNames: List<String>,
        private val resolveTags: (Collection<String>) -> Set<Tag>,
    ) {
        private var resolvedTags: Set<Tag>? = null

        fun resolve(): Set<Tag> {
            if (tagNames.isEmpty()) {
                return emptySet()
            }
            resolvedTags?.let { return it }
            return resolveTags(tagNames).also { resolvedTags = it }
        }
    }
}
