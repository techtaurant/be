package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.link.dto.LinkBatchRunResponse
import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import com.techtaurant.mainserver.link.enums.LinkCrawlRunTriggerType
import com.techtaurant.mainserver.link.infrastructure.out.LinkCrawlBatchRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@DisplayName("LinkBatchScheduler 테스트")
class LinkBatchSchedulerTest {
    private val linkCrawlBatchRepository: LinkCrawlBatchRepository = mockk()
    private val linkBatchRunService: LinkBatchRunService = mockk()
    private val fixedNow: Instant = Instant.parse("2026-04-25T08:00:30Z")

    private val scheduler =
        LinkBatchScheduler(
            linkCrawlBatchRepository = linkCrawlBatchRepository,
            linkBatchRunService = linkBatchRunService,
        )

    @Test
    @DisplayName("현재 시각 이전에 실행되어야 하는 활성 배치를 자동 실행한다")
    fun runDueBatchesRunsEligibleBatches() {
        val dueBatch = createBatch(cronExpression = "0 * * * * *")
        every { linkCrawlBatchRepository.findAllByActiveTrue() } returns listOf(dueBatch)
        every { linkBatchRunService.run(dueBatch.id!!, LinkCrawlRunTriggerType.SCHEDULED) } returns mockk()

        scheduler.runDueBatches(fixedNow)

        verify(exactly = 1) { linkBatchRunService.run(dueBatch.id!!, LinkCrawlRunTriggerType.SCHEDULED) }
    }

    @Test
    @DisplayName("같은 주기에 이미 실행된 배치는 다시 실행하지 않는다")
    fun runDueBatchesSkipsAlreadyTriggeredBatch() {
        val alreadyTriggeredBatch =
            createBatch(
                cronExpression = "0 * * * * *",
                lastTriggeredAt = Instant.parse("2026-04-25T08:00:05Z"),
            )
        every { linkCrawlBatchRepository.findAllByActiveTrue() } returns listOf(alreadyTriggeredBatch)
        every { linkBatchRunService.run(any(), any()) } returns
            LinkBatchRunResponse(
                collectedCount = 0,
                newLinkCount = 0,
                existingLinkCount = 0,
                skippedCount = 0,
            )

        scheduler.runDueBatches(fixedNow)

        verify(exactly = 0) { linkBatchRunService.run(any(), any()) }
    }

    private fun createBatch(
        cronExpression: String,
        lastTriggeredAt: Instant? = null,
    ): LinkCrawlBatch {
        val companyUser =
            User(
                name = "토스",
                email = "toss@example.com",
                provider = OAuthProvider.SYSTEM,
                identifier = "company-${UUID.randomUUID()}",
                role = UserRole.COMPANY,
                profileImageUrl = "https://example.com/toss.png",
            )

        return LinkCrawlBatch(
            companyUser = companyUser,
            name = "토스 엔지니어링",
            baseUrl = "https://toss.tech",
            pageUriTemplate = "/category/engineering?page={page}",
            itemSelector = "a[href^='/article/']",
            articleLinkSelector = ":self",
            titleSelector = "div.title",
            cronExpression = cronExpression,
            lastTriggeredAt = lastTriggeredAt,
        ).apply {
            id = UUID.randomUUID()
        }
    }
}
