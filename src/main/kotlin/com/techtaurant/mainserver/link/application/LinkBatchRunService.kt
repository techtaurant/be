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

    fun run(
        batchId: UUID,
        triggerType: LinkCrawlRunTriggerType = LinkCrawlRunTriggerType.MANUAL,
    ): LinkBatchRunResponse {
        val runId = createRun(batchId, triggerType)
        return runCatching {
            executeCrawlRun(runId)
        }.getOrElse { exception ->
            transactionOperations.execute<Unit> {
                markRunFailed(runId, exception)
            }
            throw exception
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
            ).id ?: throw IllegalStateException("실행 ID가 없습니다")
        } ?: throw IllegalStateException("실행 이력을 생성하지 못했습니다")
    }

    private fun executeCrawlRun(runId: UUID): LinkBatchRunResponse {
        return transactionOperations.execute<LinkBatchRunResponse> {
            val run = findRunOrThrow(runId)
            val batch = run.batch
            val tagResolver = LinkTagResolver(resolveLinkTagNames(batch.tagNames), tagWriteService::resolveTags)
            val crawlResult = crawl(batch, tagResolver)
            crawlResult.failedJobs.forEach { failedJob -> recordFailedJob(run, failedJob) }
            val result = crawlResult.response

            completeRun(run, result)
            result
        } ?: throw IllegalStateException("크롤 실행 결과가 없습니다")
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

    private fun findManualRetryFailedJobIds(runId: UUID): List<UUID> {
        return transactionOperations.execute<List<UUID>> {
            if (!linkCrawlRunRepository.existsById(runId)) {
                throw ApiException(LinkStatus.LINK_CRAWL_RUN_NOT_FOUND)
            }

            linkCrawlFailedJobRepository
                .findAllByRunIdAndResolvedFalseOrderByCreatedAtAsc(runId, LinkCrawlFailedJobRetryPolicy.pageRequest())
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
            if (failedJob.resolved) {
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
        val tagResolver = LinkTagResolver(resolveLinkTagNames(batch.tagNames), tagWriteService::resolveTags)

        return runCatching {
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

    private fun refreshRunStatus(run: LinkCrawlRun) {
        val runId = run.id ?: return
        run.status =
            when {
                run.status == LinkCrawlRunStatus.FAILED -> LinkCrawlRunStatus.FAILED
                linkCrawlFailedJobRepository.existsByRunIdAndResolvedFalse(runId) -> LinkCrawlRunStatus.UNRESOLVED
                run.failedJobCount > 0 -> LinkCrawlRunStatus.RESOLVED
                else -> LinkCrawlRunStatus.COMPLETED
            }
    }

    private fun summarizeRetryRun(runId: UUID): LinkFailedJobRetrySummary {
        return transactionOperations.execute<LinkFailedJobRetrySummary> {
            val run = findRunOrThrow(runId)
            refreshRunStatus(run)
            LinkFailedJobRetrySummary(
                stillUnresolvedCount = linkCrawlFailedJobRepository.countByRunIdAndResolvedFalse(runId).toInt(),
                runStatus = run.status,
            )
        } ?: throw IllegalStateException("실패 잡 재시도 결과를 요약하지 못했습니다")
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

    private fun markRunFailed(
        runId: UUID,
        exception: Throwable,
    ) {
        val run = linkCrawlRunRepository.findById(runId).orElse(null) ?: return
        run.status = LinkCrawlRunStatus.FAILED
        run.errorStatusCode = exception.toErrorStatusCode()
        run.errorMessage = exception.toErrorMessage()
        run.finishedAt = Instant.now()
        run.batch.lastTriggeredAt = Instant.now()
    }

    private fun findRunOrThrow(runId: UUID): LinkCrawlRun {
        return linkCrawlRunRepository.findById(runId).orElseThrow {
            ApiException(LinkStatus.LINK_CRAWL_RUN_NOT_FOUND)
        }
    }

    private fun resolveSnapshotForFailedJob(retryContext: LinkFailedJobRetryContext): LinkSnapshot {
        val snapshotFromSourcePage =
            crawlDocumentParser.fetchPageOrNull(retryContext.sourcePageUrl)
                ?.select(retryContext.selectors.itemSelector)
                ?.firstNotNullOfOrNull { item ->
                    val articleUrl = crawlDocumentParser.extractArticleUrl(item, retryContext.selectors, retryContext.sourcePageUrl)
                    if (articleUrl == retryContext.articleUrl) {
                        crawlDocumentParser.extractSnapshot(item, retryContext.selectors, retryContext.sourcePageUrl)
                    } else {
                        null
                    }
                }

        if (snapshotFromSourcePage != null) {
            return snapshotFromSourcePage
        }

        val title =
            retryContext.title?.trim()?.takeIf(String::isNotEmpty)
                ?: throw ApiException(LinkStatus.LINK_CRAWL_BATCH_NOT_CRAWLABLE)
        val createdAt =
            crawlDocumentParser.parseCreatedAtFromArticlePage(retryContext.articleUrl, retryContext.selectors.createdAtSelectors)
                ?: throw ApiException(LinkStatus.LINK_CRAWL_BATCH_CREATED_AT_REQUIRED)

        return LinkSnapshot(
            title = title,
            url = retryContext.articleUrl,
            summary = retryContext.summary.orEmpty(),
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

    private data class LinkFailedJobRetryContext(
        val articleUrl: String,
        val sourcePageUrl: String,
        val title: String?,
        val summary: String?,
        val selectors: LinkCrawlSelectors,
    ) {
        companion object {
            fun from(failedJob: LinkCrawlFailedJob): LinkFailedJobRetryContext {
                return LinkFailedJobRetryContext(
                    articleUrl = failedJob.articleUrl,
                    sourcePageUrl = failedJob.sourcePageUrl,
                    title = failedJob.title,
                    summary = failedJob.summary,
                    selectors = LinkCrawlSelectors.from(failedJob.run.batch),
                )
            }
        }
    }

    private data class LinkFailedJobRetrySummary(
        val stillUnresolvedCount: Int,
        val runStatus: LinkCrawlRunStatus,
    )

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
