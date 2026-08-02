package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.link.dto.LinkCursorV1
import com.techtaurant.mainserver.link.entity.Link
import com.techtaurant.mainserver.link.entity.LinkDailyStats
import com.techtaurant.mainserver.link.entity.UserLink
import com.techtaurant.mainserver.link.enums.LinkPeriod
import com.techtaurant.mainserver.link.enums.LinkSortType
import com.techtaurant.mainserver.post.entity.Tag
import com.techtaurant.mainserver.post.infrastructure.out.TagRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

@Transactional
@ActiveProfiles("test")
@DisplayName("LinkRepositoryCustomImpl 통합 테스트")
class LinkRepositoryCustomImplTest : IntegrationTest() {
    @Autowired
    private lateinit var linkRepository: LinkRepository

    @Autowired
    private lateinit var linkDailyStatsRepository: LinkDailyStatsRepository

    @Autowired
    private lateinit var userLinkRepository: UserLinkRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var tagRepository: TagRepository

    private lateinit var company: User

    @BeforeEach
    fun setUpTestData() {
        linkDailyStatsRepository.deleteAllInBatch()
        userLinkRepository.deleteAllInBatch()
        linkRepository.deleteAll()

        company =
            userRepository.save(
                User(
                    name = "토스",
                    email = "company-${UUID.randomUUID()}@toss.im",
                    provider = OAuthProvider.SYSTEM,
                    identifier = "company-${UUID.randomUUID()}",
                    role = UserRole.COMPANY,
                    profileImageUrl = "https://example.com/toss.png",
                ),
            )
    }

    @Test
    @DisplayName("PUBLISHED 정렬은 생성일 내림차순으로 정렬한다")
    fun findPublicLinkIds_published_ordersByCreatedAtDesc() {
        val newest = createLink(createdAt = Instant.parse("2026-04-03T00:00:00Z"))
        val middle = createLink(createdAt = Instant.parse("2026-04-02T00:00:00Z"))
        val oldest = createLink(createdAt = Instant.parse("2026-04-01T00:00:00Z"))

        val result =
            linkRepository.findPublicLinkIds(
                cursor = null,
                limit = 10,
                sortType = LinkSortType.PUBLISHED,
                period = LinkPeriod.ALL,
                sourceCompanyUserId = null,
                tag = null,
            )

        assertThat(result.map { it.linkId })
            .containsExactly(newest.id, middle.id, oldest.id)
    }

    @Test
    @DisplayName("PUBLISHED 정렬은 period 기준 생성일 필터를 적용한다")
    fun findPublicLinkIds_published_filtersByCreatedAtPeriod() {
        val recent = createLink(createdAtDaysAgo = 1)
        val stale = createLink(createdAtDaysAgo = 8)

        val result =
            linkRepository.findPublicLinkIds(
                cursor = null,
                limit = 10,
                sortType = LinkSortType.PUBLISHED,
                period = LinkPeriod.WEEK,
                sourceCompanyUserId = null,
                tag = null,
            )

        assertThat(result.map { it.linkId }).containsExactly(recent.id)
        assertThat(result.map { it.linkId }).doesNotContain(stale.id)
    }

    @Test
    @DisplayName("PUBLISHED 정렬 커서는 생성일 기준으로 다음 페이지를 이어 조회한다")
    fun findPublicLinkIds_published_paginatesByCursor() {
        val newest = createLink(createdAt = Instant.parse("2026-04-03T00:00:00Z"))
        val middle = createLink(createdAt = Instant.parse("2026-04-02T00:00:00Z"))
        val oldest = createLink(createdAt = Instant.parse("2026-04-01T00:00:00Z"))

        val cursor =
            LinkCursorV1(
                sortType = LinkSortType.PUBLISHED,
                sortValue = 0,
                sortInstant = middle.createdAt,
                id = middle.id!!,
            )

        val result =
            linkRepository.findPublicLinkIds(
                cursor = cursor,
                limit = 10,
                sortType = LinkSortType.PUBLISHED,
                period = LinkPeriod.ALL,
                sourceCompanyUserId = null,
                tag = null,
            )

        assertThat(result.map { it.linkId }).containsExactly(oldest.id)
        assertThat(result.map { it.linkId }).doesNotContain(newest.id, middle.id)
    }

    @Test
    @DisplayName("LIKE 정렬은 생성일이 아니라 기간 내 일별 좋아요 집계 합으로 정렬하고 필터링한다")
    fun findPublicLinkIds_like_usesPeriodDailyStats() {
        val recentTopLikes = createLink(createdAtDaysAgo = 500)
        createDailyStats(recentTopLikes, daysAgo = 0, likeCount = 5)

        val recentFewerLikes = createLink(createdAtDaysAgo = 400)
        createDailyStats(recentFewerLikes, daysAgo = 3, likeCount = 2)

        val noStats = createLink(createdAtDaysAgo = 1)

        val staleLikes = createLink(createdAtDaysAgo = 300)
        createDailyStats(staleLikes, daysAgo = 31, likeCount = 99)

        val result =
            linkRepository.findPublicLinkIds(
                cursor = null,
                limit = 10,
                sortType = LinkSortType.LIKE,
                period = LinkPeriod.MONTH,
                sourceCompanyUserId = null,
                tag = null,
            )

        assertThat(result.map { it.linkId }).containsExactly(recentTopLikes.id, recentFewerLikes.id)
        assertThat(result.map { it.sortValue }).containsExactly(5L, 2L)
        assertThat(result.map { it.linkId }).doesNotContain(noStats.id, staleLikes.id)
    }

    @Test
    @DisplayName("LIKE 정렬 커서는 전체 누적이 아니라 기간 집계 합으로 다음 페이지를 조회한다")
    fun findPublicLinkIds_like_cursorUsesPeriodSortValue() {
        val firstPageLast = createLink(createdAtDaysAgo = 500)
        createDailyStats(firstPageLast, daysAgo = 0, likeCount = 10)

        val secondPageFirst = createLink(createdAtDaysAgo = 400)
        createDailyStats(secondPageFirst, daysAgo = 0, likeCount = 5)

        val secondPageSecond = createLink(createdAtDaysAgo = 300)
        createDailyStats(secondPageSecond, daysAgo = 0, likeCount = 1)

        val cursor =
            LinkCursorV1(
                sortType = LinkSortType.LIKE,
                sortValue = 10,
                sortInstant = firstPageLast.createdAt,
                id = firstPageLast.id!!,
            )

        val result =
            linkRepository.findPublicLinkIds(
                cursor = cursor,
                limit = 10,
                sortType = LinkSortType.LIKE,
                period = LinkPeriod.MONTH,
                sourceCompanyUserId = null,
                tag = null,
            )

        assertThat(result.map { it.linkId }).containsExactly(secondPageFirst.id, secondPageSecond.id)
    }

    @Test
    @DisplayName("SAVE 정렬은 기간 내 일별 저장 집계 합으로 정렬한다")
    fun findPublicLinkIds_save_usesPeriodDailyStats() {
        val mostSaved = createLink(createdAtDaysAgo = 10)
        createDailyStats(mostSaved, daysAgo = 1, saveCount = 7)

        val fewerSaved = createLink(createdAtDaysAgo = 10)
        createDailyStats(fewerSaved, daysAgo = 2, saveCount = 3)

        val result =
            linkRepository.findPublicLinkIds(
                cursor = null,
                limit = 10,
                sortType = LinkSortType.SAVE,
                period = LinkPeriod.MONTH,
                sourceCompanyUserId = null,
                tag = null,
            )

        assertThat(result.map { it.linkId }).containsExactly(mostSaved.id, fewerSaved.id)
        assertThat(result.map { it.sortValue }).containsExactly(7L, 3L)
    }

    @Test
    @DisplayName("sourceCompanyUserId와 tag 필터를 적용한다")
    fun findPublicLinkIds_filtersBySourceCompanyUserIdAndTag() {
        val springTag = tagRepository.save(Tag(name = "Spring"))
        val companySpringLink =
            createLink(createdAt = Instant.parse("2026-04-03T00:00:00Z"), sourceCompany = company, tags = mutableSetOf(springTag))
        createLink(createdAt = Instant.parse("2026-04-02T00:00:00Z"), sourceCompany = company)
        createLink(createdAt = Instant.parse("2026-04-01T00:00:00Z"))

        val bySource =
            linkRepository.findPublicLinkIds(
                cursor = null,
                limit = 10,
                sortType = LinkSortType.PUBLISHED,
                period = LinkPeriod.ALL,
                sourceCompanyUserId = company.id,
                tag = null,
            )
        assertThat(bySource).hasSize(2)

        val byTag =
            linkRepository.findPublicLinkIds(
                cursor = null,
                limit = 10,
                sortType = LinkSortType.PUBLISHED,
                period = LinkPeriod.ALL,
                sourceCompanyUserId = null,
                tag = "Spring",
            )
        assertThat(byTag.map { it.linkId }).containsExactly(companySpringLink.id)
    }

    @Test
    @DisplayName("save는 조회 이후 원자적으로 증가한 조회수/추천수를 덮어쓰지 않는다")
    fun save_doesNotOverwriteAtomicallyIncrementedCounters() {
        // given
        val linkId = createLink().id!!
        val staleLink = linkRepository.findById(linkId).orElseThrow()

        linkRepository.incrementViewCount(linkId)
        linkRepository.incrementLikeCount(linkId)

        // when
        staleLink.title = "크롤로 갱신된 제목"
        linkRepository.save(staleLink)

        // then
        val reloadedLink = linkRepository.findById(linkId).orElseThrow()
        assertThat(reloadedLink.title).isEqualTo("크롤로 갱신된 제목")
        assertThat(reloadedLink.viewCount).isEqualTo(1L)
        assertThat(reloadedLink.likeCount).isEqualTo(1L)
    }

    private fun createLink(
        createdAtDaysAgo: Long = 0,
        createdAt: Instant = Instant.now().minus(createdAtDaysAgo, ChronoUnit.DAYS),
        sourceCompany: User? = null,
        tags: MutableSet<Tag> = mutableSetOf(),
    ): Link {
        val link =
            linkRepository.save(
                Link(
                    title = "링크",
                    url = "https://example.com/${UUID.randomUUID()}",
                    summary = "요약",
                    tags = tags,
                    createdAt = createdAt,
                ),
            )
        sourceCompany?.let { userLinkRepository.save(UserLink(user = it, link = link)) }
        link.createdAt = createdAt
        link.updatedAt = createdAt
        return linkRepository.saveAndFlush(link)
    }

    private fun createDailyStats(
        link: Link,
        daysAgo: Long,
        likeCount: Long = 0,
        saveCount: Long = 0,
    ): LinkDailyStats =
        linkDailyStatsRepository.save(
            LinkDailyStats(
                link = link,
                statDate = LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo),
                likeCount = likeCount,
                saveCount = saveCount,
            ),
        )
}
