package com.techtaurant.mainserver.link.infrastructure.`in`

import com.sun.net.httpserver.HttpServer
import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.link.entity.Link
import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import com.techtaurant.mainserver.link.entity.UserLink
import com.techtaurant.mainserver.link.infrastructure.out.LinkCrawlBatchRepository
import com.techtaurant.mainserver.link.infrastructure.out.LinkCrawlFailedJobRepository
import com.techtaurant.mainserver.link.infrastructure.out.LinkCrawlRunRepository
import com.techtaurant.mainserver.link.infrastructure.out.LinkRepository
import com.techtaurant.mainserver.link.infrastructure.out.UserLinkRepository
import com.techtaurant.mainserver.post.infrastructure.out.TagRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("AdminLinkCrawlBatchController 통합 테스트")
class AdminLinkCrawlBatchControllerIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var linkRepository: LinkRepository

    @Autowired
    private lateinit var userLinkRepository: UserLinkRepository

    @Autowired
    private lateinit var tagRepository: TagRepository

    @Autowired
    private lateinit var linkCrawlBatchRepository: LinkCrawlBatchRepository

    @Autowired
    private lateinit var linkCrawlRunRepository: LinkCrawlRunRepository

    @Autowired
    private lateinit var linkCrawlFailedJobRepository: LinkCrawlFailedJobRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    private lateinit var adminUser: User
    private lateinit var companyUser: User
    private lateinit var adminAccessToken: String
    private lateinit var httpServer: HttpServer
    private lateinit var crawlerBaseUrl: String
    private val pageRequestCounts = ConcurrentHashMap<Int, AtomicInteger>()

    @BeforeEach
    fun setUpTestData() {
        userRepository.deleteAllInBatch()
        pageRequestCounts.clear()

        adminUser =
            userRepository.save(
                User(
                    name = "관리자",
                    email = "admin-${UUID.randomUUID()}@example.com",
                    provider = OAuthProvider.GOOGLE,
                    identifier = "admin-id-${UUID.randomUUID()}",
                    role = UserRole.ADMIN,
                    profileImageUrl = "https://example.com/admin-profile.jpg",
                ),
            )

        companyUser =
            userRepository.save(
                User(
                    name = "토스",
                    email = "contact@toss.im",
                    provider = OAuthProvider.SYSTEM,
                    identifier = "company-toss",
                    role = UserRole.COMPANY,
                    profileImageUrl = "https://example.com/toss.png",
                ),
            )

        adminAccessToken = jwtTokenProvider.createAccessToken(adminUser.id!!, adminUser.role)

        httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.createContext("/category/engineering") { exchange ->
            val page = resolvePage(exchange.requestURI.query)
            pageRequestCounts.computeIfAbsent(page) { AtomicInteger() }.incrementAndGet()

            val html =
                when (page) {
                    1 ->
                        """
                        <html>
                          <body>
                            <div class="article-card">
                              <a class="article-link" href="/article/metric-review">
                                <div class="title">Metric Review, 실행을 이끌다</div>
                                <div class="summary">인사이트는 있는데 실행이 느릴 때, 지표 리뷰로 실행 리듬을 만든 이야기입니다.</div>
                                <div class="author">박종익</div>
                                <div class="published-date o6bzluc">2026년 4월 20일</div>
                              </a>
                            </div>
                            <div class="article-card">
                              <a class="article-link" href="/article/starrocks">
                                <div class="title">StarRocks 운영기</div>
                                <div class="summary">서비스 쿼리가 밀리기 시작했을 때 우리가 선택한 멀티테넌트 격리 전략을 정리했습니다.</div>
                                <div class="author">이유진</div>
                                <div class="published-date o6bzluc">2026년 4월 19일</div>
                              </a>
                            </div>
                          </body>
                        </html>
                        """.trimIndent()
                    2 ->
                        """
                        <html>
                          <body>
                            <div class="article-card">
                              <a class="article-link" href="/article/cache-layer">
                                <div class="title">Cache Layer 개선기</div>
                                <div class="summary">반복 조회 부하를 낮추기 위해 캐시 계층을 재설계한 경험을 정리했습니다.</div>
                                <div class="author">김도현</div>
                                <div class="published-date o6bzluc">2026년 4월 18일</div>
                              </a>
                            </div>
                          </body>
                        </html>
                        """.trimIndent()
                    99 -> longTitlePageHtml()
                    else -> null
                }

            if (html == null) {
                exchange.sendResponseHeaders(HttpStatus.NOT_FOUND.value(), -1)
                exchange.close()
                return@createContext
            }

            val bytes = html.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(HttpStatus.OK.value(), bytes.size.toLong())
            exchange.responseBody.use { body -> body.write(bytes) }
        }
        httpServer.createContext("/article") { exchange ->
            val html =
                when (exchange.requestURI.path) {
                    "/article/metric-review" ->
                        articleDetailHtml(
                            title = "Metric Review, 실행을 이끌다",
                            summary = "인사이트는 있는데 실행이 느릴 때, 지표 리뷰로 실행 리듬을 만든 이야기입니다.",
                            createdAtText = "2026년 4월 20일",
                        )
                    "/article/starrocks" ->
                        articleDetailHtml(
                            title = "StarRocks 운영기",
                            summary = "서비스 쿼리가 밀리기 시작했을 때 우리가 선택한 멀티테넌트 격리 전략을 정리했습니다.",
                            createdAtText = "2026년 4월 19일",
                        )
                    "/article/cache-layer" ->
                        articleDetailHtml(
                            title = "Cache Layer 개선기",
                            summary = "반복 조회 부하를 낮추기 위해 캐시 계층을 재설계한 경험을 정리했습니다.",
                            createdAtText = "2026년 4월 18일",
                        )
                    else -> null
                }

            if (html == null) {
                exchange.sendResponseHeaders(HttpStatus.NOT_FOUND.value(), -1)
                exchange.close()
                return@createContext
            }

            val bytes = html.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(HttpStatus.OK.value(), bytes.size.toLong())
            exchange.responseBody.use { body -> body.write(bytes) }
        }
        httpServer.start()

        crawlerBaseUrl = "http://localhost:${httpServer.address.port}"
    }

    @AfterEach
    fun tearDownServer() {
        httpServer.stop(0)
    }

    @Test
    @DisplayName("관리자는 회사 배치를 등록하고 수동 실행으로 링크를 수집할 수 있다")
    fun adminCanCreateBatchAndRunManually() {
        val batchId =
            given()
                .contentType("application/json")
                .header("Authorization", "Bearer $adminAccessToken")
                .body(
                    """
                    {
                      "name": "토스 엔지니어링 링크 수집",
                      "baseUrl": "$crawlerBaseUrl",
                      "pageUriTemplate": "/category/engineering?page={page}",
                      "itemSelector": ".article-card",
                      "articleLinkSelector": "a.article-link",
                      "titleSelector": ".title",
                      "summarySelector": ".summary",
                      "createdAtSelectors": ["div.o6bzluc"],
                      "tagNames": ["engineering", "backend"],
                      "cronExpression": "0 0 * * * *",
                      "startPage": 1,
                      "active": true
                    }
                    """.trimIndent(),
                ).`when`()
                .post("/admin/companies/${companyUser.id}/link-crawl-batches")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("data.name", equalTo("토스 엔지니어링 링크 수집"))
                .body("data", not(hasKey("canCrawl")))
                .body("data.tagNames", hasSize<Any>(2))
                .extract()
                .path<String>("data.id")
        assertEquals(1, pageRequestCount(1))
        assertEquals(0, pageRequestCount(2))
        assertTrue(linkRepository.findAll().isEmpty())

        given()
            .header("Authorization", "Bearer $adminAccessToken")
            .`when`()
            .post("/admin/link-crawl-batches/$batchId/runs")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.collectedCount", equalTo(3))
            .body("data.newLinkCount", equalTo(3))
            .body("data.existingLinkCount", equalTo(0))
            .body("data.skippedCount", equalTo(0))

        val savedLinks = linkRepository.findAllWithTags()
        assertEquals(3, savedLinks.size)
        assertEquals(
            3,
            userLinkRepository.findByUserIdAndLinkIdIn(companyUser.id!!, savedLinks.map { it.id!! }).size,
        )
        assertTrue(savedLinks.all { it.tags.map { tag -> tag.name }.containsAll(listOf("engineering", "backend")) })
        assertEquals(
            "2026-04-20T00:00:00Z",
            savedLinks.first { it.url.endsWith("/article/metric-review") }.createdAt.toString(),
        )

        val savedBatch = linkCrawlBatchRepository.findById(UUID.fromString(batchId)).orElseThrow()
        savedBatch.tagNames = "new-tag"
        linkCrawlBatchRepository.saveAndFlush(savedBatch)

        given()
            .header("Authorization", "Bearer $adminAccessToken")
            .`when`()
            .post("/admin/link-crawl-batches/$batchId/runs")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.collectedCount", equalTo(2))
            .body("data.newLinkCount", equalTo(0))
            .body("data.existingLinkCount", equalTo(2))

        assertEquals(3, pageRequestCount(1))
        assertEquals(1, pageRequestCount(2))
        assertTrue(linkRepository.findAllWithTags().all { link -> link.tags.none { it.name == "new-tag" } })
        assertEquals(null, tagRepository.findByName("new-tag"))
    }

    @Test
    @DisplayName("수집 링크 필드가 DB 길이를 초과해도 정상 링크와 실패 잡을 함께 기록한다")
    fun runRecordsFailedJobAndKeepsValidLinksWhenCrawledFieldsExceedDatabaseLimits() {
        val batch =
            linkCrawlBatchRepository.save(
                LinkCrawlBatch(
                    companyUser = companyUser,
                    name = "긴 제목 실패 기록 배치",
                    baseUrl = crawlerBaseUrl,
                    pageUriTemplate = "/category/engineering?page={page}",
                    itemSelector = ".article-card",
                    articleLinkSelector = "a.article-link",
                    titleSelector = ".title",
                    summarySelector = ".summary",
                    createdAtSelectors = "div.o6bzluc",
                    cronExpression = "0 0 * * * *",
                    startPage = 99,
                    active = true,
                    tagNames = "engineering",
                ),
            )

        given()
            .header("Authorization", "Bearer $adminAccessToken")
            .`when`()
            .post("/admin/link-crawl-batches/${batch.id}/runs")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.collectedCount", equalTo(1))
            .body("data.newLinkCount", equalTo(1))
            .body("data.failedJobCount", equalTo(2))

        val savedLinks = linkRepository.findAll()
        assertEquals(1, savedLinks.size)
        assertEquals("$crawlerBaseUrl/article/valid-after-long-title", savedLinks.single().url)

        val savedRun = linkCrawlRunRepository.findAllByBatchIdOrderByStartedAtDesc(batch.id!!).single()
        val failedJobs = linkCrawlFailedJobRepository.findAllByRunIdAndResolvedAtIsNullOrderByCreatedAtAsc(savedRun.id!!)
        assertEquals(2, failedJobs.size)
        assertTrue(failedJobs.any { it.articleUrl == "$crawlerBaseUrl/article/too-long-title" })
        assertTrue(failedJobs.any { it.articleUrl.length == 2048 })
    }

    @Test
    @DisplayName("배치 등록 시 생성일을 수집할 수 없으면 등록이 실패한다")
    fun createBatchFailsWhenCreatedAtCannotBeCollected() {
        given()
            .contentType("application/json")
            .header("Authorization", "Bearer $adminAccessToken")
            .body(
                """
                {
                  "name": "날짜 selector 오류 배치",
                  "baseUrl": "$crawlerBaseUrl",
                  "pageUriTemplate": "/category/engineering?page={page}",
                  "itemSelector": ".article-card",
                  "articleLinkSelector": "a.article-link",
                  "titleSelector": ".title",
                  "summarySelector": ".summary",
                  "createdAtSelectors": [".missing-date"],
                  "tagNames": ["engineering"],
                  "cronExpression": "0 0 * * * *",
                  "startPage": 1,
                  "active": true
                }
                """.trimIndent(),
            ).`when`()
            .post("/admin/companies/${companyUser.id}/link-crawl-batches")
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())
            .body("status", equalTo(6006))

        assertEquals(1, pageRequestCount(1))
        assertTrue(linkCrawlBatchRepository.findAll().isEmpty())
    }

    @Test
    @DisplayName("관리자는 실행 이력을 조회하고 미해소 실패 잡을 재시도해 모두 해소되면 RESOLVED 상태가 된다")
    fun adminCanReviewRunsAndRetryUnresolvedFailedJobsUntilResolved() {
        val batch =
            linkCrawlBatchRepository.save(
                LinkCrawlBatch(
                    companyUser = companyUser,
                    name = "날짜 selector 오류 배치",
                    baseUrl = crawlerBaseUrl,
                    pageUriTemplate = "/category/engineering?page={page}",
                    itemSelector = ".article-card",
                    articleLinkSelector = "a.article-link",
                    titleSelector = ".title",
                    summarySelector = ".summary",
                    createdAtSelectors = ".missing-date",
                    cronExpression = "0 0 * * * *",
                    startPage = 1,
                    active = true,
                    tagNames = "engineering",
                ),
            )

        given()
            .header("Authorization", "Bearer $adminAccessToken")
            .`when`()
            .post("/admin/link-crawl-batches/${batch.id}/runs")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.collectedCount", equalTo(0))
            .body("data.newLinkCount", equalTo(0))
            .body("data.failedJobCount", equalTo(3))

        given()
            .header("Authorization", "Bearer $adminAccessToken")
            .`when`()
            .get("/admin/link-crawl-batches/${batch.id}/runs")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data", hasSize<Any>(1))
            .body("data[0].batchId", equalTo(batch.id.toString()))
            .body("data[0].triggerType", equalTo("MANUAL"))
            .body("data[0].status", equalTo("UNRESOLVED"))
            .body("data[0].failedJobCount", equalTo(3))
            .body("data[0].hasUnresolvedFailedJobs", equalTo(true))

        val runId = linkCrawlRunRepository.findAllByBatchIdOrderByStartedAtDesc(batch.id!!).single().id!!

        given()
            .header("Authorization", "Bearer $adminAccessToken")
            .`when`()
            .get("/admin/link-crawl-runs/$runId/failed-jobs")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data", hasSize<Any>(3))
            .body("data[0].runId", equalTo(runId.toString()))
            .body("data[0].batchId", equalTo(batch.id.toString()))
            .body("data[0]", not(hasKey("resolved")))
            .body("data[0].resolvedAt", equalTo(null))
            .body("data[0].failureCount", equalTo(1))
            .body("data[0].errorStatusCode", equalTo(6006))

        batch.createdAtSelectors = "div.o6bzluc"
        linkCrawlBatchRepository.saveAndFlush(batch)

        given()
            .header("Authorization", "Bearer $adminAccessToken")
            .`when`()
            .post("/admin/link-crawl-runs/$runId/failed-job-retries")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.retriedCount", equalTo(3))
            .body("data.resolvedCount", equalTo(3))
            .body("data.stillUnresolvedCount", equalTo(0))
            .body("data.runStatus", equalTo("RESOLVED"))

        assertEquals(3, linkRepository.findAll().size)

        given()
            .header("Authorization", "Bearer $adminAccessToken")
            .`when`()
            .get("/admin/link-crawl-runs/$runId/failed-jobs")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data", hasSize<Any>(0))

        given()
            .header("Authorization", "Bearer $adminAccessToken")
            .`when`()
            .get("/admin/link-crawl-batches/${batch.id}/runs")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data", hasSize<Any>(1))
            .body("data[0].status", equalTo("RESOLVED"))
            .body("data[0].hasUnresolvedFailedJobs", equalTo(false))
    }

    @Test
    @DisplayName("기존 URL이 새 회사에 연결되면 다음 페이지까지 수집한다")
    fun runBatchContinuesWhenExistingLinksAreNewlyConnectedToCompany() {
        val anotherCompany =
            userRepository.save(
                User(
                    name = "다른 회사",
                    email = "another-${UUID.randomUUID()}@example.com",
                    provider = OAuthProvider.SYSTEM,
                    identifier = "company-another-${UUID.randomUUID()}",
                    role = UserRole.COMPANY,
                    profileImageUrl = "https://example.com/another.png",
                ),
            )
        val existingLinks =
            listOf(
                saveExistingLinkForSource(anotherCompany, "/article/metric-review", "Metric Review, 실행을 이끌다"),
                saveExistingLinkForSource(anotherCompany, "/article/starrocks", "StarRocks 운영기"),
                saveExistingLinkForSource(anotherCompany, "/article/cache-layer", "Cache Layer 개선기"),
            )

        val batch =
            linkCrawlBatchRepository.save(
                LinkCrawlBatch(
                    companyUser = companyUser,
                    name = "토스 기존 링크 연결 배치",
                    baseUrl = crawlerBaseUrl,
                    pageUriTemplate = "/category/engineering?page={page}",
                    itemSelector = ".article-card",
                    articleLinkSelector = "a.article-link",
                    titleSelector = ".title",
                    summarySelector = ".summary",
                    createdAtSelectors = "div.o6bzluc",
                    cronExpression = "0 0 * * * *",
                    startPage = 1,
                    active = true,
                    tagNames = "engineering",
                ),
            )

        given()
            .header("Authorization", "Bearer $adminAccessToken")
            .`when`()
            .post("/admin/link-crawl-batches/${batch.id}/runs")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.collectedCount", equalTo(3))
            .body("data.newLinkCount", equalTo(0))
            .body("data.existingLinkCount", equalTo(3))
            .body("data.skippedCount", equalTo(0))

        val existingLinkIds = existingLinks.map { it.id!! }
        assertEquals(1, pageRequestCount(1))
        assertEquals(1, pageRequestCount(2))
        assertEquals(3, userLinkRepository.findByUserIdAndLinkIdIn(companyUser.id!!, existingLinkIds).size)
        assertEquals(3, userLinkRepository.findByUserIdAndLinkIdIn(anotherCompany.id!!, existingLinkIds).size)
    }

    @Test
    @DisplayName("관리자는 회사 배치를 조회하고 수정할 수 있다")
    fun adminCanListAndUpdateCompanyBatches() {
        val batch =
            linkCrawlBatchRepository.save(
                LinkCrawlBatch(
                    companyUser = companyUser,
                    name = "초기 배치",
                    baseUrl = crawlerBaseUrl,
                    pageUriTemplate = "/category/engineering?page={page}",
                    itemSelector = ".article-card",
                    articleLinkSelector = "a.article-link",
                    titleSelector = ".title",
                    summarySelector = ".summary",
                    createdAtSelectors = "div.o6bzluc",
                    cronExpression = "0 0 * * * *",
                    startPage = 1,
                    active = true,
                    tagNames = "engineering\nbackend",
                ),
            )

        given()
            .header("Authorization", "Bearer $adminAccessToken")
            .`when`()
            .get("/admin/companies/${companyUser.id}/link-crawl-batches")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data", hasSize<Any>(1))
            .body("data[0].name", equalTo("초기 배치"))
            .body("data[0].baseUrl", equalTo(crawlerBaseUrl))
            .body("data[0]", not(hasKey("pageUriTemplate")))
            .body("data[0]", not(hasKey("itemSelector")))
            .body("data[0]", not(hasKey("tagNames")))
            .body("data[0]", not(hasKey("canCrawl")))

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer $adminAccessToken")
            .body(
                """
                {
                  "name": "수정된 배치",
                  "active": false,
                  "tagNames": ["infra"]
                }
                """.trimIndent(),
            ).`when`()
            .patch("/admin/link-crawl-batches/${batch.id}")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.name", equalTo("수정된 배치"))
            .body("data.active", equalTo(false))
            .body("data", not(hasKey("canCrawl")))
            .body("data.tagNames", hasSize<Any>(1))
            .body("data.tagNames[0]", equalTo("infra"))

        assertEquals(1, pageRequestCount(1))
        assertEquals(0, pageRequestCount(2))
        assertTrue(linkRepository.findAll().isEmpty())
    }

    private fun resolvePage(query: String?): Int {
        return query?.split("&")
            ?.mapNotNull { parameter ->
                val parts = parameter.split("=", limit = 2)
                if (parts.firstOrNull() == "page") {
                    parts.getOrNull(1)?.toIntOrNull()
                } else {
                    null
                }
            }?.firstOrNull()
            ?: 1
    }

    private fun saveExistingLinkForSource(
        sourceCompanyUser: User,
        path: String,
        title: String,
    ): Link {
        val link =
            linkRepository.saveAndFlush(
                Link(
                    title = title,
                    url = "$crawlerBaseUrl$path",
                    summary = "$title summary",
                ).apply {
                    createdAt = Instant.parse("2026-04-20T00:00:00Z")
                },
            )
        userLinkRepository.saveAndFlush(UserLink(user = sourceCompanyUser, link = link))

        return link
    }

    private fun longTitlePageHtml(): String {
        val tooLongTitle = "가".repeat(201)
        val tooLongUrl = "/article/${"u".repeat(2050)}"
        return """
            <html>
              <body>
                <div class="article-card">
                  <a class="article-link" href="/article/too-long-title">
                    <div class="title">$tooLongTitle</div>
                    <div class="summary">DB 제목 길이를 초과하는 글입니다.</div>
                    <div class="published-date o6bzluc">2026년 4월 17일</div>
                  </a>
                </div>
                <div class="article-card">
                  <a class="article-link" href="$tooLongUrl">
                    <div class="title">URL 길이 초과 글</div>
                    <div class="summary">DB URL 길이를 초과하는 글입니다.</div>
                    <div class="published-date o6bzluc">2026년 4월 17일</div>
                  </a>
                </div>
                <div class="article-card">
                  <a class="article-link" href="/article/valid-after-long-title">
                    <div class="title">긴 제목 뒤 정상 글</div>
                    <div class="summary">앞선 실패 뒤에도 저장되어야 하는 글입니다.</div>
                    <div class="published-date o6bzluc">2026년 4월 16일</div>
                  </a>
                </div>
              </body>
            </html>
            """.trimIndent()
    }

    private fun articleDetailHtml(
        title: String,
        summary: String,
        createdAtText: String,
    ): String {
        return """
            <html>
              <body>
                <div class="title">$title</div>
                <div class="summary">$summary</div>
                <div class="o6bzluc">$createdAtText</div>
              </body>
            </html>
            """.trimIndent()
    }

    private fun pageRequestCount(page: Int): Int {
        return pageRequestCounts[page]?.get() ?: 0
    }
}
