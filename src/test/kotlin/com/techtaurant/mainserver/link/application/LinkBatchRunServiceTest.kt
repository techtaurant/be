package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.common.exception.ApiException
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
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

@DisplayName("LinkBatchRunService 테스트")
class LinkBatchRunServiceTest {
    private val linkCrawlBatchRepository: LinkCrawlBatchRepository = mockk()
    private val linkCrawlRunRepository: LinkCrawlRunRepository = mockk(relaxed = true)
    private val linkCrawlFailedJobRepository: LinkCrawlFailedJobRepository = mockk(relaxed = true)
    private val linkRepository: LinkRepository = mockk(relaxed = true)
    private val userLinkRepository: UserLinkRepository = mockk(relaxed = true)
    private val tagWriteService: TagWriteService = mockk(relaxed = true)
    private val linkDocumentFetcher = StubLinkDocumentFetcher()
    private val linkBatchRunService =
        LinkBatchRunService(
            linkCrawlBatchRepository = linkCrawlBatchRepository,
            linkCrawlRunRepository = linkCrawlRunRepository,
            linkCrawlFailedJobRepository = linkCrawlFailedJobRepository,
            linkRepository = linkRepository,
            userLinkRepository = userLinkRepository,
            tagWriteService = tagWriteService,
            linkDocumentFetcher = linkDocumentFetcher,
            transactionOperations = ImmediateTransactionOperations(),
        )

    private fun captureSavedRun(runId: UUID = UUID.randomUUID()): CapturingSlot<LinkCrawlRun> {
        val savedRun = slot<LinkCrawlRun>()
        every { linkCrawlRunRepository.save(capture(savedRun)) } answers {
            savedRun.captured.apply { id = runId }
        }
        every { linkCrawlRunRepository.findById(runId) } answers {
            Optional.of(savedRun.captured)
        }
        return savedRun
    }

    @Test
    @DisplayName("크롤링 가능한 첫 페이지이면 검증을 통과한다")
    fun validateCrawlablePassesWhenFirstPageCanBeCrawled() {
        val batch = createBatch(createdAtSelectors = ".created-date")
        linkDocumentFetcher.html = crawlableHtml()

        linkBatchRunService.validateCrawlable(batch)

        verify(exactly = 0) { linkRepository.save(any()) }
    }

    @Test
    @DisplayName("점 구분 생성일이면 크롤링 가능 검증을 통과한다")
    fun validateCrawlablePassesWhenCreatedAtUsesDottedDate() {
        val batch = createBatch(createdAtSelectors = ".created-date")
        linkDocumentFetcher.html = crawlableHtml(createdAtText = "2026. 6. 12")

        linkBatchRunService.validateCrawlable(batch)

        verify(exactly = 0) { linkRepository.save(any()) }
    }

    @Test
    @DisplayName("목록에 생성일이 없으면 아티클 상세 페이지에서 생성일을 수집한다")
    fun validateCrawlablePassesWhenCreatedAtOnlyExistsOnArticlePage() {
        val batch = createBatch(createdAtSelectors = ".created-date")
        linkDocumentFetcher.setHtml("https://example.com/articles?page=1", listHtmlWithoutCreatedAt())
        linkDocumentFetcher.setHtml("https://example.com/article/metric-review", articleDetailHtml())

        linkBatchRunService.validateCrawlable(batch)

        verify(exactly = 0) { linkRepository.save(any()) }
    }

    @Test
    @DisplayName("목록과 상세 페이지 모두 생성일이 없으면 검증이 실패한다")
    fun validateCrawlableFailsWhenCreatedAtMissingOnBothListAndArticlePage() {
        val batch = createBatch(createdAtSelectors = ".created-date")
        linkDocumentFetcher.setHtml("https://example.com/articles?page=1", listHtmlWithoutCreatedAt())
        linkDocumentFetcher.setHtml(
            "https://example.com/article/metric-review",
            "<html><body><div class=\"title\">상세</div></body></html>",
        )

        val exception =
            assertFailsWith<ApiException> {
                linkBatchRunService.validateCrawlable(batch)
            }

        assertEquals(LinkStatus.LINK_CRAWL_BATCH_CREATED_AT_REQUIRED, exception.status)
    }

    @Test
    @DisplayName("첫 페이지에 수집 가능한 항목이 없으면 검증이 실패한다")
    fun validateCrawlableFailsWhenNoItemCanBeCrawled() {
        val batch = createBatch(createdAtSelectors = ".created-date")
        linkDocumentFetcher.html = "<html><body></body></html>"

        val exception =
            assertFailsWith<ApiException> {
                linkBatchRunService.validateCrawlable(batch)
            }

        assertEquals(LinkStatus.LINK_CRAWL_BATCH_NOT_CRAWLABLE, exception.status)
    }

    @Test
    @DisplayName("검증 중 생성일을 수집할 수 없으면 검증이 실패한다")
    fun validateCrawlableFailsWhenCreatedAtCannotBeCollected() {
        val batch = createBatch(createdAtSelectors = ".missing-date")
        linkDocumentFetcher.html = crawlableHtml()

        val exception =
            assertFailsWith<ApiException> {
                linkBatchRunService.validateCrawlable(batch)
            }

        assertEquals(LinkStatus.LINK_CRAWL_BATCH_CREATED_AT_REQUIRED, exception.status)
    }

    @Test
    @DisplayName("실패 없이 완료되면 실행 이력이 COMPLETED 상태로 기록된다")
    fun runRecordsCompletedRunWhenNoLinkFails() {
        val batchId = UUID.randomUUID()
        val batch = createBatch(createdAtSelectors = ".created-date").apply { id = batchId }
        linkDocumentFetcher.setHtml("https://example.com/articles?page=1", crawlableHtml())
        val savedRun = captureSavedRun()
        every { linkCrawlBatchRepository.findById(batchId) } returns Optional.of(batch)
        every { linkRepository.findByUrl("https://example.com/article/metric-review") } returns null
        every { linkRepository.save(any<Link>()) } answers {
            (invocation.args[0] as Link).apply { id = UUID.randomUUID() }
        }
        every { userLinkRepository.findByUserIdAndLinkId(any(), any()) } returns null
        every { userLinkRepository.save(any()) } answers { invocation.args[0] as UserLink }

        val response = linkBatchRunService.run(batchId)

        assertEquals(1, response.newLinkCount)
        assertEquals(0, response.failedJobCount)
        assertEquals(LinkCrawlRunStatus.COMPLETED, savedRun.captured.status)
        assertEquals(0, savedRun.captured.failedJobCount)
        verify(exactly = 0) { linkCrawlFailedJobRepository.save(any()) }
    }

    @Test
    @DisplayName("시작 페이지를 가져오지 못하면 실행 이력을 FAILED 상태로 기록하고 예외를 전파한다")
    fun runRecordsFailedRunAndRethrowsWhenStartPageCannotBeFetched() {
        val batchId = UUID.randomUUID()
        val batch = createBatch(createdAtSelectors = ".created-date").apply { id = batchId }
        val pageUrl = "https://example.com/articles?page=1"
        val savedRun = captureSavedRun()
        linkDocumentFetcher.setFailure(pageUrl, HttpStatusException("not found", 404, pageUrl))
        every { linkCrawlBatchRepository.findById(batchId) } returns Optional.of(batch)

        val exception =
            assertFailsWith<ApiException> {
                linkBatchRunService.run(batchId)
            }

        assertEquals(LinkStatus.LINK_CRAWL_BATCH_NOT_CRAWLABLE, exception.status)
        assertEquals(LinkCrawlRunStatus.FAILED, savedRun.captured.status)
        assertEquals(LinkStatus.LINK_CRAWL_BATCH_NOT_CRAWLABLE.getCustomStatusCode(), savedRun.captured.errorStatusCode)
        assertEquals(exception.detail, savedRun.captured.errorMessage)
        assertNotNull(batch.lastTriggeredAt)
    }

    @Test
    @DisplayName("배치 실행 중 개별 링크 실패를 실행 이력에 기록하고 다음 링크 수집을 계속한다")
    fun runRecordsFailedJobUnderRunAndContinuesWhenSingleLinkCannotBeProcessed() {
        val batchId = UUID.randomUUID()
        val batch = createBatch(createdAtSelectors = ".created-date").apply { id = batchId }
        val pageUrl = "https://example.com/articles?page=1"
        linkDocumentFetcher.setHtml(pageUrl, mixedCrawlHtml())
        linkDocumentFetcher.setHtml("https://example.com/article/missing-date", articleDetailWithoutCreatedAt())
        val savedRun = captureSavedRun()
        every { linkCrawlBatchRepository.findById(batchId) } returns Optional.of(batch)
        every { linkCrawlFailedJobRepository.findByRunIdAndArticleUrl(any(), any()) } returns null
        every { linkCrawlFailedJobRepository.save(any()) } answers { invocation.args[0] as LinkCrawlFailedJob }
        every { tagWriteService.resolveTags(any()) } returns emptySet()
        every { linkRepository.findByUrl("https://example.com/article/valid") } returns null
        every { linkRepository.save(any<Link>()) } answers {
            (invocation.args[0] as Link).apply { id = UUID.randomUUID() }
        }
        every { userLinkRepository.findByUserIdAndLinkId(any(), any()) } returns null
        every { userLinkRepository.save(any()) } answers { invocation.args[0] as UserLink }

        val response = linkBatchRunService.run(batchId)

        assertEquals(1, response.collectedCount)
        assertEquals(1, response.newLinkCount)
        assertEquals(0, response.existingLinkCount)
        assertEquals(0, response.skippedCount)
        assertEquals(1, response.failedJobCount)
        assertEquals(LinkCrawlRunStatus.UNRESOLVED, savedRun.captured.status)
        assertEquals(1, savedRun.captured.failedJobCount)
        verify(exactly = 1) { linkRepository.save(any()) }
        verify(exactly = 1) {
            linkCrawlFailedJobRepository.save(
                match {
                    it.run.batch.id == batchId &&
                        it.sourcePage == 1 &&
                        it.sourcePageUrl == pageUrl &&
                        it.articleUrl == "https://example.com/article/missing-date" &&
                        it.title == "생성일 없는 글" &&
                        !it.resolved &&
                        it.errorStatusCode == LinkStatus.LINK_CRAWL_BATCH_CREATED_AT_REQUIRED.getCustomStatusCode()
                },
            )
        }
    }

    @Test
    @DisplayName("한 페이지의 모든 링크가 실패해도 다음 페이지 수집을 계속한다")
    fun runContinuesToNextPageWhenEveryLinkOnPageIsRecordedAsFailed() {
        val batchId = UUID.randomUUID()
        val batch = createBatch(createdAtSelectors = ".created-date").apply { id = batchId }
        val firstPageUrl = "https://example.com/articles?page=1"
        val secondPageUrl = "https://example.com/articles?page=2"
        linkDocumentFetcher.setHtml(firstPageUrl, failingOnlyHtml())
        linkDocumentFetcher.setHtml("https://example.com/article/missing-date", articleDetailWithoutCreatedAt())
        linkDocumentFetcher.setHtml(secondPageUrl, crawlableHtml())
        captureSavedRun()
        every { linkCrawlBatchRepository.findById(batchId) } returns Optional.of(batch)
        every { linkCrawlFailedJobRepository.findByRunIdAndArticleUrl(any(), any()) } returns null
        every { linkCrawlFailedJobRepository.save(any()) } answers { invocation.args[0] as LinkCrawlFailedJob }
        every { linkRepository.findByUrl("https://example.com/article/metric-review") } returns null
        every { linkRepository.save(any<Link>()) } answers {
            (invocation.args[0] as Link).apply { id = UUID.randomUUID() }
        }

        val response = linkBatchRunService.run(batchId)

        assertEquals(1, response.failedJobCount)
        assertEquals(1, response.newLinkCount)
        verify(exactly = 1) { linkRepository.save(any()) }
    }

    @Test
    @DisplayName("반복 페이지가 이미 실패로 관측한 링크만 포함하면 다음 페이지를 조회하지 않는다")
    fun runStopsWhenRepeatedPageContainsOnlyAlreadyFailedLink() {
        val batchId = UUID.randomUUID()
        val batch = createBatch(createdAtSelectors = ".created-date").apply { id = batchId }
        val firstPageUrl = "https://example.com/articles?page=1"
        val secondPageUrl = "https://example.com/articles?page=2"
        val thirdPageUrl = "https://example.com/articles?page=3"
        linkDocumentFetcher.setHtml(firstPageUrl, failingOnlyHtml())
        linkDocumentFetcher.setHtml(secondPageUrl, failingOnlyHtml())
        linkDocumentFetcher.setHtml(thirdPageUrl, crawlableHtml())
        linkDocumentFetcher.setHtml("https://example.com/article/missing-date", articleDetailWithoutCreatedAt())
        captureSavedRun()
        every { linkCrawlBatchRepository.findById(batchId) } returns Optional.of(batch)
        every { linkCrawlFailedJobRepository.findByRunIdAndArticleUrl(any(), any()) } returns null
        every { linkCrawlFailedJobRepository.save(any()) } answers { invocation.args[0] as LinkCrawlFailedJob }
        every { linkRepository.findByUrl("https://example.com/article/metric-review") } returns null

        val response = linkBatchRunService.run(batchId)

        assertEquals(0, response.newLinkCount)
        verify(exactly = 0) { linkRepository.save(any()) }
    }

    @Test
    @DisplayName("자동 재시도는 활성 배치, 백오프, 최대 실패 횟수, 배치 크기 조건으로 대상만 조회한다")
    fun retryAllUnresolvedFailedJobsLimitsAutomaticRetryCandidates() {
        val now = Instant.parse("2026-07-02T10:00:00Z")
        val retryableBefore = now.minus(Duration.ofMinutes(30))
        val pageable = PageRequest.of(0, 50)
        every {
            linkCrawlFailedJobRepository.findRetryableAutomaticJobs(
                maxFailureCount = 3,
                retryableBefore = retryableBefore,
                pageable = pageable,
            )
        } returns emptyList()

        linkBatchRunService.retryAllUnresolvedFailedJobs(now)

        verify(exactly = 1) {
            linkCrawlFailedJobRepository.findRetryableAutomaticJobs(
                maxFailureCount = 3,
                retryableBefore = retryableBefore,
                pageable = pageable,
            )
        }
    }

    @Test
    @DisplayName("수동 재시도도 배치 크기 조건으로 미해소 실패 잡을 조회한다")
    fun retryRunFailedJobsLimitsManualRetryCandidates() {
        val runId = UUID.randomUUID()
        val batch = createBatch(createdAtSelectors = ".created-date")
        val run =
            LinkCrawlRun(
                batch = batch,
                triggerType = LinkCrawlRunTriggerType.MANUAL,
                status = LinkCrawlRunStatus.UNRESOLVED,
                failedJobCount = 1,
                startedAt = Instant.parse("2026-07-02T10:00:00Z"),
                finishedAt = Instant.parse("2026-07-02T10:00:00Z"),
            ).apply { id = runId }
        val pageable = PageRequest.of(0, 50)
        every { linkCrawlRunRepository.existsById(runId) } returns true
        every { linkCrawlRunRepository.findById(runId) } returns Optional.of(run)
        every {
            linkCrawlFailedJobRepository.findAllByRunIdAndResolvedFalseOrderByCreatedAtAsc(runId, pageable)
        } returns emptyList()
        every { linkCrawlFailedJobRepository.existsByRunIdAndResolvedFalse(runId) } returns false
        every { linkCrawlFailedJobRepository.countByRunIdAndResolvedFalse(runId) } returns 0L

        val response = linkBatchRunService.retryRunFailedJobs(runId)

        assertEquals(0, response.retriedCount)
        assertEquals(0, response.resolvedCount)
        assertEquals(0, response.stillUnresolvedCount)
        assertEquals(LinkCrawlRunStatus.RESOLVED, response.runStatus)
        verify(exactly = 1) {
            linkCrawlFailedJobRepository.findAllByRunIdAndResolvedFalseOrderByCreatedAtAsc(runId, pageable)
        }
    }

    @Test
    @DisplayName("자동 재시도 대상 조회 후 비활성화된 배치의 실패 잡은 재시도하지 않는다")
    fun retryAllUnresolvedFailedJobsSkipsJobWhenBatchBecomesInactive() {
        val now = Instant.parse("2026-07-02T10:00:00Z")
        val batch = createBatch(createdAtSelectors = ".created-date").apply { active = false }
        val run =
            LinkCrawlRun(
                batch = batch,
                triggerType = LinkCrawlRunTriggerType.MANUAL,
                status = LinkCrawlRunStatus.UNRESOLVED,
                failedJobCount = 1,
                startedAt = now.minus(Duration.ofHours(1)),
                finishedAt = now.minus(Duration.ofHours(1)),
            ).apply { id = UUID.randomUUID() }
        val failedJob =
            LinkCrawlFailedJob(
                run = run,
                sourcePage = 1,
                sourcePageUrl = "https://example.com/articles?page=1",
                articleUrl = "https://example.com/article/retry",
                title = "재시도 대상",
                summary = "재시도 대상 요약",
                errorStatusCode = LinkStatus.LINK_CRAWL_BATCH_CREATED_AT_REQUIRED.getCustomStatusCode(),
                errorMessage = "생성일 없음",
                failureCount = 1,
                lastFailedAt = now.minus(Duration.ofHours(1)),
            ).apply { id = UUID.randomUUID() }
        every { linkCrawlFailedJobRepository.findRetryableAutomaticJobs(any(), any(), any()) } returns listOf(failedJob)
        every { linkCrawlFailedJobRepository.findById(failedJob.id!!) } returns Optional.of(failedJob)

        linkBatchRunService.retryAllUnresolvedFailedJobs(now)

        verify(exactly = 0) { linkRepository.findByUrl(any()) }
        verify(exactly = 0) { linkCrawlFailedJobRepository.save(any()) }
    }

    private fun createBatch(createdAtSelectors: String): LinkCrawlBatch {
        return LinkCrawlBatch(
            companyUser =
                User(
                    name = "토스",
                    email = "company@example.com",
                    provider = OAuthProvider.SYSTEM,
                    identifier = "company",
                    role = UserRole.COMPANY,
                    profileImageUrl = "https://example.com/company.png",
                ).apply { id = UUID.randomUUID() },
            name = "토스 링크 수집",
            baseUrl = "https://example.com",
            pageUriTemplate = "/articles?page={page}",
            itemSelector = ".article-card",
            articleLinkSelector = "a.article-link",
            titleSelector = ".title",
            summarySelector = ".summary",
            createdAtSelectors = createdAtSelectors,
            cronExpression = "0 0 * * * *",
            startPage = 1,
            active = true,
            tagNames = "engineering",
        )
    }

    private fun crawlableHtml(createdAtText: String = "2026년 4월 20일"): String {
        return """
            <html>
              <body>
                <div class="article-card">
                  <a class="article-link" href="/article/metric-review">
                    <div class="title">Metric Review, 실행을 이끌다</div>
                    <div class="summary">지표 리뷰로 실행 리듬을 만든 이야기입니다.</div>
                    <div class="created-date">$createdAtText</div>
                  </a>
                </div>
              </body>
            </html>
            """.trimIndent()
    }

    private fun mixedCrawlHtml(): String {
        return """
            <html>
              <body>
                <div class="article-card">
                  <a class="article-link" href="/article/missing-date">
                    <div class="title">생성일 없는 글</div>
                    <div class="summary">생성일 selector가 맞지 않는 글입니다.</div>
                  </a>
                </div>
                <div class="article-card">
                  <a class="article-link" href="/article/valid">
                    <div class="title">정상 수집 글</div>
                    <div class="summary">정상적으로 수집되는 글입니다.</div>
                    <div class="created-date">2026년 4월 21일</div>
                  </a>
                </div>
              </body>
            </html>
            """.trimIndent()
    }

    private fun failingOnlyHtml(): String {
        return """
            <html>
              <body>
                <div class="article-card">
                  <a class="article-link" href="/article/missing-date">
                    <div class="title">생성일 없는 글</div>
                    <div class="summary">생성일 selector가 맞지 않는 글입니다.</div>
                  </a>
                </div>
              </body>
            </html>
            """.trimIndent()
    }

    private fun articleDetailWithoutCreatedAt(): String {
        return """
            <html>
              <body>
                <h1>생성일 없는 글</h1>
              </body>
            </html>
            """.trimIndent()
    }

    private fun listHtmlWithoutCreatedAt(): String {
        return """
            <html>
              <body>
                <div class="article-card">
                  <a class="article-link" href="/article/metric-review">
                    <div class="title">Metric Review, 실행을 이끌다</div>
                    <div class="summary">지표 리뷰로 실행 리듬을 만든 이야기입니다.</div>
                  </a>
                </div>
              </body>
            </html>
            """.trimIndent()
    }

    private fun articleDetailHtml(createdAtText: String = "2026년 4월 20일"): String {
        return """
            <html>
              <body>
                <h1>Metric Review, 실행을 이끌다</h1>
                <div class="created-date">$createdAtText</div>
              </body>
            </html>
            """.trimIndent()
    }

    private class StubLinkDocumentFetcher : LinkDocumentFetcher {
        var html: String = ""
        private val htmlByUrl = mutableMapOf<String, String>()
        private val failureByUrl = mutableMapOf<String, Throwable>()

        fun setHtml(
            url: String,
            value: String,
        ) {
            htmlByUrl[url] = value
        }

        fun setFailure(
            url: String,
            throwable: Throwable,
        ) {
            failureByUrl[url] = throwable
        }

        override fun fetch(url: String): Document {
            failureByUrl[url]?.let { throw it }
            return Jsoup.parse(htmlByUrl[url] ?: html, url)
        }
    }

    private class ImmediateTransactionOperations : TransactionOperations {
        override fun <T : Any?> execute(action: TransactionCallback<T>): T? {
            return action.doInTransaction(SimpleTransactionStatus())
        }
    }
}
