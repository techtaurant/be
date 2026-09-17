package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import com.techtaurant.mainserver.link.entity.LinkCrawlRun
import com.techtaurant.mainserver.link.enums.LinkCrawlRunStatus
import com.techtaurant.mainserver.link.enums.LinkCrawlRunTriggerType
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
import java.util.UUID

@Transactional
@ActiveProfiles("test")
@DisplayName("LinkCrawlFailedJobRepositoryCustomImpl 통합 테스트")
class LinkCrawlFailedJobRepositoryCustomImplTest : IntegrationTest() {
    @Autowired
    private lateinit var linkCrawlFailedJobRepository: LinkCrawlFailedJobRepository

    @Autowired
    private lateinit var linkCrawlRunRepository: LinkCrawlRunRepository

    @Autowired
    private lateinit var linkCrawlBatchRepository: LinkCrawlBatchRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    private lateinit var batch: LinkCrawlBatch
    private lateinit var run: LinkCrawlRun

    @BeforeEach
    fun setUpTestData() {
        val company =
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
        batch =
            linkCrawlBatchRepository.save(
                LinkCrawlBatch(
                    companyUser = company,
                    name = "실패 잡 저장소 테스트 배치",
                    baseUrl = "https://example.com",
                    pageUriTemplate = "/articles?page={page}",
                    itemSelector = ".article-card",
                    articleLinkSelector = "a",
                    titleSelector = ".title",
                    cronExpression = "0 0 * * * *",
                ),
            )
        run = saveRun()
    }

    @Test
    @DisplayName("해소됐던 URL이 다시 실패로 기록되면 재시도 횟수를 1로 초기화하고 미해소로 되돌린다")
    fun recordFailureRestartsFailureCountWhenResolvedArticleUrlFailsAgain() {
        // given
        recordFailure(run.id!!)
        recordFailure(run.id!!)
        linkCrawlFailedJobRepository.markResolvedIfUnresolved(batch.id!!, ARTICLE_URL, Instant.parse("2026-07-02T00:00:00Z"))
        val nextRun = saveRun()

        // when
        recordFailure(nextRun.id!!)

        // then
        val failedJob = linkCrawlFailedJobRepository.findByBatchIdAndArticleUrl(batch.id!!, ARTICLE_URL)!!
        assertThat(failedJob.failureCount).isEqualTo(1)
        assertThat(failedJob.resolvedAt).isNull()
        assertThat(failedJob.lastRun?.id).isEqualTo(nextRun.id)
    }

    @Test
    @DisplayName("미해소 URL이 다시 실패로 기록되면 같은 행의 재시도 횟수를 누적한다")
    fun recordFailureAccumulatesFailureCountOnSameRowWhenArticleUrlIsUnresolved() {
        // given
        recordFailure(run.id!!)
        val failedJobId = linkCrawlFailedJobRepository.findByBatchIdAndArticleUrl(batch.id!!, ARTICLE_URL)!!.id

        // when
        recordFailure(run.id!!)

        // then
        val failedJob = linkCrawlFailedJobRepository.findByBatchIdAndArticleUrl(batch.id!!, ARTICLE_URL)!!
        assertThat(failedJob.id).isEqualTo(failedJobId)
        assertThat(failedJob.failureCount).isEqualTo(2)
    }

    @Test
    @DisplayName("이미 해소된 실패 잡을 다시 해소하면 false를 돌려주고 처음 해소 시각을 유지한다")
    fun markResolvedIfUnresolvedKeepsFirstResolutionWhenAlreadyResolved() {
        // given
        recordFailure(run.id!!)
        val firstResolvedAt = Instant.parse("2026-07-02T00:00:00Z")
        val isFirstResolved = linkCrawlFailedJobRepository.markResolvedIfUnresolved(batch.id!!, ARTICLE_URL, firstResolvedAt)

        // when
        val isSecondResolved =
            linkCrawlFailedJobRepository.markResolvedIfUnresolved(batch.id!!, ARTICLE_URL, Instant.parse("2026-07-03T00:00:00Z"))

        // then
        assertThat(isFirstResolved).isTrue()
        assertThat(isSecondResolved).isFalse()
        assertThat(linkCrawlFailedJobRepository.findByBatchIdAndArticleUrl(batch.id!!, ARTICLE_URL)!!.resolvedAt).isEqualTo(firstResolvedAt)
    }

    @Test
    @DisplayName("재시도 도중 다른 경로가 해소한 실패 잡에는 재시도 실패를 덮어쓰지 않는다")
    fun recordRetryFailureIfUnresolvedDoesNotOverwriteResolvedFailedJob() {
        // given
        recordFailure(run.id!!)
        val failedJobId = linkCrawlFailedJobRepository.findByBatchIdAndArticleUrl(batch.id!!, ARTICLE_URL)!!.id!!
        linkCrawlFailedJobRepository.markResolvedIfUnresolved(batch.id!!, ARTICLE_URL, Instant.parse("2026-07-02T00:00:00Z"))

        // when
        linkCrawlFailedJobRepository.recordRetryFailureIfUnresolved(failedJobId, 500, "재시도 실패", Instant.parse("2026-07-03T00:00:00Z"))

        // then
        val failedJob = linkCrawlFailedJobRepository.findById(failedJobId).get()
        assertThat(failedJob.resolvedAt).isNotNull()
        assertThat(failedJob.failureCount).isEqualTo(1)
        assertThat(failedJob.errorMessage).isEqualTo(INITIAL_ERROR_MESSAGE)
    }

    @Test
    @DisplayName("미해소 실패 잡의 재시도 실패는 재시도 횟수와 오류를 갱신한다")
    fun recordRetryFailureIfUnresolvedUpdatesUnresolvedFailedJob() {
        // given
        recordFailure(run.id!!)
        val failedJobId = linkCrawlFailedJobRepository.findByBatchIdAndArticleUrl(batch.id!!, ARTICLE_URL)!!.id!!

        // when
        linkCrawlFailedJobRepository.recordRetryFailureIfUnresolved(failedJobId, 500, "재시도 실패", Instant.parse("2026-07-03T00:00:00Z"))

        // then
        val failedJob = linkCrawlFailedJobRepository.findById(failedJobId).get()
        assertThat(failedJob.failureCount).isEqualTo(2)
        assertThat(failedJob.errorStatusCode).isEqualTo(500)
        assertThat(failedJob.errorMessage).isEqualTo("재시도 실패")
    }

    private fun recordFailure(lastRunId: UUID) {
        linkCrawlFailedJobRepository.recordFailure(
            batchId = batch.id!!,
            articleUrl = ARTICLE_URL,
            lastRunId = lastRunId,
            errorStatusCode = 6006,
            errorMessage = INITIAL_ERROR_MESSAGE,
            failedAt = Instant.parse("2026-07-01T00:00:00Z"),
        )
    }

    private fun saveRun(): LinkCrawlRun =
        linkCrawlRunRepository.save(
            LinkCrawlRun(
                batch = batch,
                triggerType = LinkCrawlRunTriggerType.MANUAL,
                status = LinkCrawlRunStatus.UNRESOLVED,
                failedJobCount = 1,
                startedAt = Instant.parse("2026-07-01T00:00:00Z"),
                finishedAt = Instant.parse("2026-07-01T00:00:00Z"),
            ),
        )

    private companion object {
        const val ARTICLE_URL = "https://example.com/article/missing-date"
        const val INITIAL_ERROR_MESSAGE = "생성일 없음"
    }
}
