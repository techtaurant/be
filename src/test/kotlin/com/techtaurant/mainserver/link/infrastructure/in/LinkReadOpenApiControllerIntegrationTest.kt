package com.techtaurant.mainserver.link.infrastructure.`in`

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.link.entity.Link
import com.techtaurant.mainserver.link.entity.LinkDailyStats
import com.techtaurant.mainserver.link.entity.UserLink
import com.techtaurant.mainserver.link.infrastructure.out.LinkDailyStatsRepository
import com.techtaurant.mainserver.link.infrastructure.out.LinkRepository
import com.techtaurant.mainserver.link.infrastructure.out.UserLinkRepository
import com.techtaurant.mainserver.post.entity.Tag
import com.techtaurant.mainserver.post.infrastructure.out.TagRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

@DisplayName("LinkReadOpenApiController 통합 테스트")
class LinkReadOpenApiControllerIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var linkRepository: LinkRepository

    @Autowired
    private lateinit var userLinkRepository: UserLinkRepository

    @Autowired
    private lateinit var tagRepository: TagRepository

    @Autowired
    private lateinit var linkDailyStatsRepository: LinkDailyStatsRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private lateinit var firstCompany: User
    private lateinit var secondCompany: User

    @BeforeEach
    fun setUpTestData() {
        firstCompany =
            userRepository.save(
                User(
                    name = "토스",
                    email = "contact-${UUID.randomUUID()}@toss.im",
                    provider = OAuthProvider.SYSTEM,
                    identifier = "company-toss-${UUID.randomUUID()}",
                    role = UserRole.COMPANY,
                    profileImageUrl = "https://example.com/toss.png",
                ),
            )

        secondCompany =
            userRepository.save(
                User(
                    name = "당근",
                    email = "contact-${UUID.randomUUID()}@daangn.com",
                    provider = OAuthProvider.SYSTEM,
                    identifier = "company-daangn-${UUID.randomUUID()}",
                    role = UserRole.COMPANY,
                    profileImageUrl = "https://example.com/daangn.png",
                ),
            )
    }

    @Test
    @DisplayName("공개 링크 목록은 정적 링크 필드와 누적 통계를 ApiResponse와 CursorPageResponse 형태로 반환한다")
    fun getLinkContents_returnsStaticFieldsWithStats() {
        val linkTag = tagRepository.save(Tag(name = "Spring"))
        val anotherLinkTag = tagRepository.save(Tag(name = "Kotlin"))
        val createdAt = Instant.parse("2026-04-25T10:15:30Z")
        val link =
            saveLink(
                title = "Public Link",
                url = "https://example.com/public-link",
                sourceCompanyUser = firstCompany,
                createdAtMillis = 1_000,
                createdAt = createdAt,
                tags = mutableSetOf(linkTag, anotherLinkTag),
            )
        userLinkRepository.saveAndFlush(UserLink(user = secondCompany, link = link))

        given()
            .queryParam("size", 1)
            .`when`()
            .get("/open-api/links")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("status", equalTo(HttpStatus.OK.value()))
            .body("message", equalTo("OK"))
            .body("data.keySet()", containsInAnyOrder("content", "nextCursor", "hasNext", "size"))
            .body("data.content", hasSize<Any>(1))
            .body(
                "data.content[0].keySet()",
                containsInAnyOrder(
                    "id",
                    "title",
                    "url",
                    "summary",
                    "sourceCompanyUserId",
                    "tags",
                    "createdAt",
                    "updatedAt",
                    "viewCount",
                    "likeCount",
                    "saveCount",
                ),
            )
            .body("data.content[0].id", equalTo(link.id.toString()))
            .body("data.content[0].title", equalTo("Public Link"))
            .body("data.content[0].url", equalTo("https://example.com/public-link"))
            .body("data.content[0].summary", equalTo("Public Link summary"))
            .body("data.content[0].sourceCompanyUserId", equalTo(firstCompany.id.toString()))
            .body("data.content[0].tags", containsInAnyOrder("Kotlin", "Spring"))
            .body("data.content[0].createdAt", equalTo("2026-04-25T10:15:30Z"))
            .body("data.content[0].updatedAt", notNullValue())
            .body("data.content[0].viewCount", equalTo(0))
            .body("data.content[0].likeCount", equalTo(0))
            .body("data.content[0].saveCount", equalTo(0))
            .body("data.content[0].isSaved", nullValue())
            .body("data.content[0].isRead", nullValue())
            .body("data.nextCursor", nullValue())
            .body("data.hasNext", equalTo(false))
            .body("data.size", equalTo(1))
    }

    @Test
    @DisplayName("공개 링크 목록은 인증 없이 cursor와 size로 생성일 최신순 페이지네이션한다")
    fun getLinkContents_paginatesByCursorWithoutAuthentication() {
        val oldest =
            saveLink(
                "Oldest",
                "https://example.com/oldest",
                firstCompany,
                4_000,
                createdAt = Instant.parse("2026-04-01T00:00:00Z"),
            )
        val older =
            saveLink(
                "Older",
                "https://example.com/older",
                firstCompany,
                5_000,
                createdAt = Instant.parse("2026-03-31T00:00:00Z"),
            )
        val middle =
            saveLink(
                "Middle",
                "https://example.com/middle",
                firstCompany,
                1_000,
                createdAt = Instant.parse("2026-04-02T00:00:00Z"),
            )
        val newest =
            saveLink(
                "Newest",
                "https://example.com/newest",
                firstCompany,
                2_000,
                createdAt = Instant.parse("2026-04-03T00:00:00Z"),
            )

        val nextCursor =
            given()
                .queryParam("size", 2)
                .`when`()
                .get("/open-api/links")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("data.content", hasSize<Any>(2))
                .body("data.content[0].id", equalTo(newest.id.toString()))
                .body("data.content[1].id", equalTo(middle.id.toString()))
                .body("data.nextCursor", notNullValue())
                .body("data.hasNext", equalTo(true))
                .body("data.size", equalTo(2))
                .extract()
                .path<String>("data.nextCursor")

        given()
            .queryParam("cursor", nextCursor)
            .queryParam("size", 1)
            .`when`()
            .get("/open-api/links")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.content", hasSize<Any>(1))
            .body("data.content[0].id", equalTo(oldest.id.toString()))
            .body("data.nextCursor", notNullValue())
            .body("data.hasNext", equalTo(true))
            .body("data.size", equalTo(1))
            .extract()
            .path<String>("data.nextCursor")
            .let { lastCursor ->
                given()
                    .queryParam("cursor", lastCursor)
                    .queryParam("size", 2)
                    .`when`()
                    .get("/open-api/links")
                    .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("data.content", hasSize<Any>(1))
                    .body("data.content[0].id", equalTo(older.id.toString()))
                    .body("data.nextCursor", nullValue())
                    .body("data.hasNext", equalTo(false))
                    .body("data.size", equalTo(1))
            }
    }

    @Test
    @DisplayName("공개 링크 목록은 size 기본값 20을 사용한다")
    fun getLinkContents_usesDefaultSizeTwenty() {
        repeat(21) { index ->
            saveLink(
                title = "Link $index",
                url = "https://example.com/default-size-$index",
                sourceCompanyUser = firstCompany,
                createdAtMillis = index.toLong(),
            )
        }

        given()
            .`when`()
            .get("/open-api/links")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.content", hasSize<Any>(20))
            .body("data.hasNext", equalTo(true))
            .body("data.size", equalTo(20))
    }

    @Test
    @DisplayName("공개 링크 목록은 sourceCompanyUserId와 tag로 필터링한다")
    fun getLinkContents_filtersBySourceCompanyUserIdAndTag() {
        val springTag = tagRepository.save(Tag(name = "Spring"))
        val kotlinTag = tagRepository.save(Tag(name = "Kotlin"))
        val firstCompanySpringLink =
            saveLink(
                title = "First Company Spring",
                url = "https://example.com/first-company-spring",
                sourceCompanyUser = firstCompany,
                createdAtMillis = 1_000,
                tags = mutableSetOf(springTag),
            )
        val firstCompanyKotlinLink =
            saveLink(
                title = "First Company Kotlin",
                url = "https://example.com/first-company-kotlin",
                sourceCompanyUser = firstCompany,
                createdAtMillis = 2_000,
                tags = mutableSetOf(kotlinTag),
            )
        val secondCompanySpringLink =
            saveLink(
                title = "Second Company Spring",
                url = "https://example.com/second-company-spring",
                sourceCompanyUser = secondCompany,
                createdAtMillis = 3_000,
                tags = mutableSetOf(springTag),
            )

        given()
            .queryParam("sourceCompanyUserId", firstCompany.id.toString())
            .`when`()
            .get("/open-api/links")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.content", hasSize<Any>(2))
            .body("data.content.id", hasItem(firstCompanySpringLink.id.toString()))
            .body("data.content.id", hasItem(firstCompanyKotlinLink.id.toString()))
            .body("data.content.id", not(hasItem(secondCompanySpringLink.id.toString())))

        given()
            .queryParam("tag", "Spring")
            .`when`()
            .get("/open-api/links")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.content", hasSize<Any>(2))
            .body("data.content.id", hasItem(firstCompanySpringLink.id.toString()))
            .body("data.content.id", hasItem(secondCompanySpringLink.id.toString()))
            .body("data.content.id", not(hasItem(firstCompanyKotlinLink.id.toString())))
    }

    @Test
    @DisplayName("공개 회사 링크 목록은 인증 없이 회사별 링크를 커서 기반으로 조회한다")
    fun getCompanyLinkContents_paginatesCompanyLinksWithoutAuthentication() {
        val oldest =
            saveLink(
                "Oldest Company Link",
                "https://example.com/company-oldest",
                firstCompany,
                3_000,
                createdAt = Instant.parse("2026-04-01T00:00:00Z"),
            )
        val middle =
            saveLink(
                "Middle Company Link",
                "https://example.com/company-middle",
                firstCompany,
                1_000,
                createdAt = Instant.parse("2026-04-02T00:00:00Z"),
            )
        val newest =
            saveLink(
                "Newest Company Link",
                "https://example.com/company-newest",
                firstCompany,
                2_000,
                createdAt = Instant.parse("2026-04-03T00:00:00Z"),
            )
        val otherCompanyLink =
            saveLink(
                "Other Company Link",
                "https://example.com/company-other",
                secondCompany,
                4_000,
                createdAt = Instant.parse("2026-04-04T00:00:00Z"),
            )

        val nextCursor =
            given()
                .queryParam("size", 2)
                .`when`()
                .get("/open-api/companies/${firstCompany.id}/links")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("data.content", hasSize<Any>(2))
                .body("data.content[0].id", equalTo(newest.id.toString()))
                .body("data.content[1].id", equalTo(middle.id.toString()))
                .body("data.content.id", not(hasItem(otherCompanyLink.id.toString())))
                .body("data.nextCursor", notNullValue())
                .body("data.hasNext", equalTo(true))
                .body("data.size", equalTo(2))
                .extract()
                .path<String>("data.nextCursor")

        given()
            .queryParam("cursor", nextCursor)
            .queryParam("size", 2)
            .`when`()
            .get("/open-api/companies/${firstCompany.id}/links")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.content", hasSize<Any>(1))
            .body("data.content[0].id", equalTo(oldest.id.toString()))
            .body("data.nextCursor", nullValue())
            .body("data.hasNext", equalTo(false))
            .body("data.size", equalTo(1))
    }

    @Test
    @DisplayName("공개 회사 링크 목록은 없는 회사 ID면 COMPANY_NOT_FOUND를 반환한다")
    fun getCompanyLinkContents_missingCompany_returnsCompanyNotFound() {
        given()
            .`when`()
            .get("/open-api/companies/${UUID.randomUUID()}/links")
            .then()
            .statusCode(HttpStatus.NOT_FOUND.value())
            .body("status", equalTo(1010))
            .body("message", equalTo("Company not found"))
    }

    @Test
    @DisplayName("공개 링크 목록은 결과가 없으면 빈 커서 페이지를 반환한다")
    fun getLinkContents_returnsEmptyPageForNoMatches() {
        saveLink("Valid Link", "https://example.com/valid-link", firstCompany, 1_000)

        given()
            .queryParam("tag", "Unknown")
            .`when`()
            .get("/open-api/links")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.content", hasSize<Any>(0))
            .body("data.nextCursor", nullValue())
            .body("data.hasNext", equalTo(false))
            .body("data.size", equalTo(0))
    }

    @Test
    @DisplayName("공개 링크 목록은 size 범위와 cursor 형식을 검증한다")
    fun getLinkContents_validatesSizeAndCursor() {
        given()
            .queryParam("size", 0)
            .`when`()
            .get("/open-api/links")
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())

        given()
            .queryParam("size", 101)
            .`when`()
            .get("/open-api/links")
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())

        given()
            .queryParam("cursor", "not-a-cursor")
            .`when`()
            .get("/open-api/links")
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())
            .body("status", equalTo(6005))
            .body("message", equalTo("Invalid link cursor"))
    }

    @Test
    @DisplayName("공개 링크 상세 조회는 정적 링크 필드와 누적 통계를 반환한다")
    fun getLinkContentDetail_returnsStaticFieldsWithStats() {
        val linkTag = tagRepository.save(Tag(name = "Architecture"))
        val anotherLinkTag = tagRepository.save(Tag(name = "Kotlin"))
        val createdAt = Instant.parse("2026-04-26T11:20:30Z")
        val link =
            saveLink(
                title = "Detail Link",
                url = "https://example.com/detail-link",
                sourceCompanyUser = firstCompany,
                createdAtMillis = 4_000,
                createdAt = createdAt,
                tags = mutableSetOf(linkTag, anotherLinkTag),
            )
        userLinkRepository.saveAndFlush(UserLink(user = secondCompany, link = link))
        createDailyStats(link, daysAgo = 1, viewCount = 12, likeCount = 4, saveCount = 2)

        given()
            .`when`()
            .get("/open-api/links/${link.id}")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("status", equalTo(HttpStatus.OK.value()))
            .body(
                "data.keySet()",
                containsInAnyOrder(
                    "id",
                    "title",
                    "url",
                    "summary",
                    "sourceCompanyUserId",
                    "tags",
                    "createdAt",
                    "updatedAt",
                    "viewCount",
                    "likeCount",
                    "saveCount",
                ),
            )
            .body("data.id", equalTo(link.id.toString()))
            .body("data.title", equalTo("Detail Link"))
            .body("data.url", equalTo("https://example.com/detail-link"))
            .body("data.summary", equalTo("Detail Link summary"))
            .body("data.sourceCompanyUserId", equalTo(firstCompany.id.toString()))
            .body("data.tags", containsInAnyOrder("Architecture", "Kotlin"))
            .body("data.createdAt", equalTo("2026-04-26T11:20:30Z"))
            .body("data.updatedAt", notNullValue())
            .body("data.viewCount", equalTo(12))
            .body("data.likeCount", equalTo(4))
            .body("data.saveCount", equalTo(2))
            .body("data.isSaved", nullValue())
            .body("data.isRead", nullValue())
    }

    @Test
    @DisplayName("공개 링크 상세 조회는 없는 링크 ID면 LINK_NOT_FOUND를 반환한다")
    fun getLinkContentDetail_missingLink_returnsLinkNotFound() {
        given()
            .`when`()
            .get("/open-api/links/${UUID.randomUUID()}")
            .then()
            .statusCode(HttpStatus.NOT_FOUND.value())
            .body("status", equalTo(6001))
            .body("data", nullValue())
            .body("message", equalTo("Link not found"))
    }

    @Test
    @DisplayName("공개 링크 목록은 PUBLISHED 정렬에서 period 기준 생성일 필터를 적용한다")
    fun getLinkContents_published_filtersByPeriod() {
        val recent =
            saveLink(
                title = "Recent",
                url = "https://example.com/${UUID.randomUUID()}",
                sourceCompanyUser = firstCompany,
                createdAtMillis = 1_000,
                createdAt = Instant.now().minus(1, ChronoUnit.DAYS),
            )
        saveLink(
            title = "Stale",
            url = "https://example.com/${UUID.randomUUID()}",
            sourceCompanyUser = firstCompany,
            createdAtMillis = 2_000,
            createdAt = Instant.now().minus(8, ChronoUnit.DAYS),
        )

        given()
            .queryParam("sort", "PUBLISHED")
            .queryParam("period", "WEEK")
            .queryParam("size", 10)
            .`when`()
            .get("/open-api/links")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.content", hasSize<Any>(1))
            .body("data.content[0].id", equalTo(recent.id.toString()))
    }

    @Test
    @DisplayName("공개 링크 목록은 LIKE 정렬에서 기간 내 일별 좋아요 집계 합 기준으로 정렬한다")
    fun getLinkContents_like_ranksByPeriodDailyStats() {
        val mostLiked = saveLinkForRanking("Most Liked", 1_000)
        val lessLiked = saveLinkForRanking("Less Liked", 2_000)
        val notLikedInPeriod = saveLinkForRanking("Stale", 3_000)
        createDailyStats(mostLiked, daysAgo = 0, likeCount = 5)
        createDailyStats(lessLiked, daysAgo = 2, likeCount = 2)
        createDailyStats(notLikedInPeriod, daysAgo = 40, likeCount = 99)

        given()
            .queryParam("sort", "LIKE")
            .queryParam("period", "MONTH")
            .queryParam("size", 10)
            .`when`()
            .get("/open-api/links")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.content", hasSize<Any>(2))
            .body("data.content[0].id", equalTo(mostLiked.id.toString()))
            .body("data.content[0].likeCount", equalTo(5))
            .body("data.content[1].id", equalTo(lessLiked.id.toString()))
            .body("data.content[1].likeCount", equalTo(2))
    }

    @Test
    @DisplayName("공개 링크 목록은 SAVE 정렬에서 기간 내 일별 저장 집계 합 기준으로 정렬한다")
    fun getLinkContents_save_ranksByPeriodDailyStats() {
        val mostSaved = saveLinkForRanking("Most Saved", 1_000)
        val lessSaved = saveLinkForRanking("Less Saved", 2_000)
        createDailyStats(mostSaved, daysAgo = 1, saveCount = 7)
        createDailyStats(lessSaved, daysAgo = 2, saveCount = 3)

        given()
            .queryParam("sort", "SAVE")
            .queryParam("period", "MONTH")
            .queryParam("size", 10)
            .`when`()
            .get("/open-api/links")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.content", hasSize<Any>(2))
            .body("data.content[0].id", equalTo(mostSaved.id.toString()))
            .body("data.content[0].saveCount", equalTo(7))
            .body("data.content[1].id", equalTo(lessSaved.id.toString()))
            .body("data.content[1].saveCount", equalTo(3))
    }

    @Test
    @DisplayName("공개 링크 커서는 발급된 정렬과 다른 정렬로 요청하면 INVALID_LINK_CURSOR를 반환한다")
    fun getLinkContents_rejectsCursorFromDifferentSort() {
        saveLinkForRanking("First", 1_000, createdAt = Instant.parse("2026-04-02T00:00:00Z"))
        saveLinkForRanking("Second", 2_000, createdAt = Instant.parse("2026-04-01T00:00:00Z"))

        val publishedCursor =
            given()
                .queryParam("size", 1)
                .`when`()
                .get("/open-api/links")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("data.nextCursor", notNullValue())
                .extract()
                .path<String>("data.nextCursor")

        given()
            .queryParam("sort", "LIKE")
            .queryParam("cursor", publishedCursor)
            .`when`()
            .get("/open-api/links")
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())
            .body("status", equalTo(6005))
            .body("message", equalTo("Invalid link cursor"))
    }

    private fun saveLink(
        title: String,
        url: String,
        sourceCompanyUser: User,
        createdAtMillis: Long,
        createdAt: Instant = Instant.ofEpochMilli(createdAtMillis),
        tags: MutableSet<Tag> = mutableSetOf(),
    ): Link {
        return TransactionTemplate(transactionManager).execute {
            val managedSourceCompanyUser =
                userRepository.findById(
                    sourceCompanyUser.id ?: throw IllegalStateException("회사 사용자 ID가 없습니다"),
                ).orElseThrow()
            val managedTags =
                tags.map { tag ->
                    tagRepository.findById(tag.id ?: throw IllegalStateException("태그 ID가 없습니다")).orElseThrow()
                }.toMutableSet()
            val link =
                linkRepository.save(
                    Link(
                        title = title,
                        url = url,
                        summary = "$title summary",
                        tags = managedTags,
                        createdAt = createdAt,
                    ),
                )
            userLinkRepository.save(UserLink(user = managedSourceCompanyUser, link = link))
            link.createdAt = createdAt
            link.updatedAt = createdAt
            linkRepository.saveAndFlush(link)
        } ?: throw IllegalStateException("링크 저장에 실패했습니다")
    }

    private fun saveLinkForRanking(
        title: String,
        createdAtMillis: Long,
        createdAt: Instant = Instant.ofEpochMilli(createdAtMillis),
    ): Link =
        saveLink(
            title = title,
            url = "https://example.com/${UUID.randomUUID()}",
            sourceCompanyUser = firstCompany,
            createdAtMillis = createdAtMillis,
            createdAt = createdAt,
        )

    private fun createDailyStats(
        link: Link,
        daysAgo: Long,
        viewCount: Long = 0,
        likeCount: Long = 0,
        saveCount: Long = 0,
    ) {
        TransactionTemplate(transactionManager).execute {
            val managedLink = linkRepository.findById(link.id!!).orElseThrow()
            linkDailyStatsRepository.saveAndFlush(
                LinkDailyStats(
                    link = managedLink,
                    statDate = LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo),
                    viewCount = viewCount,
                    likeCount = likeCount,
                    saveCount = saveCount,
                ),
            )
        }
    }
}
