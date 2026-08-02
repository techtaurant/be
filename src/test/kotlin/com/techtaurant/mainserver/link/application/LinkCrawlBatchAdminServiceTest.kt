package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.link.dto.CreateLinkCrawlBatchRequest
import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import com.techtaurant.mainserver.link.enums.LinkCrawlRunTriggerType
import com.techtaurant.mainserver.link.infrastructure.out.LinkCrawlBatchRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verifyOrder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals

@DisplayName("LinkCrawlBatchAdminService 테스트")
class LinkCrawlBatchAdminServiceTest {
    private val linkCrawlBatchRepository: LinkCrawlBatchRepository = mockk()
    private val userRepository: UserRepository = mockk()
    private val linkBatchRunService: LinkBatchRunService = mockk()
    private val linkCrawlBatchAdminService =
        LinkCrawlBatchAdminService(
            linkCrawlBatchRepository = linkCrawlBatchRepository,
            userRepository = userRepository,
            linkBatchRunService = linkBatchRunService,
        )

    @Test
    @DisplayName("배치 등록 전 크롤링 가능 여부를 검증한다")
    fun createBatchValidatesCrawlableBatch() {
        val companyUser = createCompanyUser()
        val batchId = UUID.randomUUID()
        lateinit var savedBatch: LinkCrawlBatch
        every { userRepository.findById(companyUser.id!!) } returns Optional.of(companyUser)
        every { linkBatchRunService.validateCrawlable(any()) } just runs
        every { linkBatchRunService.run(batchId, any()) } returns mockk()
        every { linkCrawlBatchRepository.save(any()) } answers {
            firstArg<LinkCrawlBatch>().apply { id = batchId }.also { savedBatch = it }
        }
        every { linkCrawlBatchRepository.findById(batchId) } answers { Optional.of(savedBatch) }

        val response =
            linkCrawlBatchAdminService.createBatch(
                companyUserId = companyUser.id!!,
                request = createRequest(),
            )

        assertEquals(batchId, response.id)
        assertEquals("토스 링크 수집", response.name)
        assertEquals(2, response.endPage)
        verifyOrder {
            linkBatchRunService.validateCrawlable(any())
            linkCrawlBatchRepository.save(any())
            linkBatchRunService.run(batchId, LinkCrawlRunTriggerType.CREATED)
        }
    }

    @Test
    @DisplayName("최초 수집 실행이 갱신한 lastTriggeredAt을 응답에 반영한다")
    fun createBatchReturnsBatchStateUpdatedByInitialRun() {
        val companyUser = createCompanyUser()
        val batchId = UUID.randomUUID()
        val initialRunTriggeredAt = Instant.parse("2026-08-01T00:00:00Z")
        val batchAfterInitialRun =
            createBatch().apply {
                id = batchId
                lastTriggeredAt = initialRunTriggeredAt
            }
        every { userRepository.findById(companyUser.id!!) } returns Optional.of(companyUser)
        every { linkBatchRunService.validateCrawlable(any()) } just runs
        every { linkBatchRunService.run(batchId, any()) } returns mockk()
        every { linkCrawlBatchRepository.save(any()) } answers {
            firstArg<LinkCrawlBatch>().apply { id = batchId }
        }
        every { linkCrawlBatchRepository.findById(batchId) } returns Optional.of(batchAfterInitialRun)

        val response =
            linkCrawlBatchAdminService.createBatch(
                companyUserId = companyUser.id!!,
                request = createRequest(),
            )

        assertEquals(initialRunTriggeredAt, response.lastTriggeredAt)
    }

    private fun createRequest(): CreateLinkCrawlBatchRequest {
        return CreateLinkCrawlBatchRequest(
            name = "토스 링크 수집",
            baseUrl = "https://example.com",
            pageUriTemplate = "/articles?page={page}",
            itemSelector = ".article-card",
            articleLinkSelector = "a.article-link",
            titleSelector = ".title",
            summarySelector = ".summary",
            createdAtSelectors = listOf(".created-date"),
            tagNames = listOf("engineering"),
            cronExpression = "0 0 * * * *",
            startPage = 1,
            endPage = 2,
            active = true,
        )
    }

    private fun createBatch(): LinkCrawlBatch {
        return LinkCrawlBatch(
            companyUser = createCompanyUser(),
            name = "토스 링크 수집",
            baseUrl = "https://example.com",
            pageUriTemplate = "/articles?page={page}",
            itemSelector = ".article-card",
            articleLinkSelector = "a.article-link",
            titleSelector = ".title",
            summarySelector = ".summary",
            createdAtSelectors = ".created-date",
            tagNames = "engineering",
            cronExpression = "0 0 * * * *",
            startPage = 1,
            active = true,
        )
    }

    private fun createCompanyUser(): User {
        return User(
            name = "토스",
            email = "company@example.com",
            provider = OAuthProvider.SYSTEM,
            identifier = "company",
            role = UserRole.COMPANY,
            profileImageUrl = "https://example.com/company.png",
        ).apply { id = UUID.randomUUID() }
    }
}
