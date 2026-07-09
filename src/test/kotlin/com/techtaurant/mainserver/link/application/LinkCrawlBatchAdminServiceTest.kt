package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.link.dto.CreateLinkCrawlBatchRequest
import com.techtaurant.mainserver.link.dto.UpdateLinkCrawlBatchRequest
import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import com.techtaurant.mainserver.link.enums.LinkStatus
import com.techtaurant.mainserver.link.infrastructure.out.LinkCrawlBatchRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
        every { userRepository.findById(companyUser.id!!) } returns Optional.of(companyUser)
        every { linkBatchRunService.validateCrawlable(any()) } just runs
        every { linkBatchRunService.run(batchId, any()) } returns mockk()
        every { linkCrawlBatchRepository.save(any()) } answers {
            firstArg<LinkCrawlBatch>().apply { id = batchId }
        }

        val response =
            linkCrawlBatchAdminService.createBatch(
                companyUserId = companyUser.id!!,
                request =
                    CreateLinkCrawlBatchRequest(
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
                    ),
            )

        assertEquals(batchId, response.id)
        assertEquals("토스 링크 수집", response.name)
        assertEquals(2, response.endPage)
        verifyOrder {
            linkBatchRunService.validateCrawlable(any())
            linkCrawlBatchRepository.save(any())
            linkBatchRunService.run(batchId, any())
        }
    }

    @Test
    @DisplayName("배치 수정 후 크롤링 가능 여부를 검증한다")
    fun updateBatchValidatesCrawlableBatch() {
        val batchId = UUID.randomUUID()
        val batch = createBatch().apply { id = batchId }
        every { linkCrawlBatchRepository.findById(batchId) } returns Optional.of(batch)
        every { linkBatchRunService.validateCrawlable(batch) } just runs

        val response =
            linkCrawlBatchAdminService.updateBatch(
                batchId = batchId,
                request = UpdateLinkCrawlBatchRequest(name = "수정된 배치", endPage = 3, active = false),
            )

        assertEquals("수정된 배치", response.name)
        assertEquals(3, response.endPage)
        assertEquals(false, response.active)
        verify(exactly = 1) { linkBatchRunService.validateCrawlable(batch) }
    }

    @Test
    @DisplayName("마지막 페이지가 시작 페이지보다 작으면 배치 등록이 실패한다")
    fun createBatchFailsWhenEndPageIsBeforeStartPage() {
        val companyUser = createCompanyUser()
        every { userRepository.findById(companyUser.id!!) } returns Optional.of(companyUser)

        val exception =
            assertFailsWith<ApiException> {
                linkCrawlBatchAdminService.createBatch(
                    companyUserId = companyUser.id!!,
                    request =
                        CreateLinkCrawlBatchRequest(
                            name = "토스 링크 수집",
                            baseUrl = "https://example.com",
                            pageUriTemplate = "/articles?page={page}",
                            itemSelector = ".article-card",
                            articleLinkSelector = "a.article-link",
                            titleSelector = ".title",
                            cronExpression = "0 0 * * * *",
                            startPage = 3,
                            endPage = 2,
                        ),
                )
            }

        assertEquals(LinkStatus.INVALID_LINK_CRAWL_BATCH_PAGE_RANGE, exception.status)
        verify(exactly = 0) { linkBatchRunService.validateCrawlable(any()) }
        verify(exactly = 0) { linkCrawlBatchRepository.save(any()) }
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
