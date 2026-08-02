package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.link.dto.CreateLinkCrawlBatchRequest
import com.techtaurant.mainserver.link.dto.LinkCrawlBatchListItemResponse
import com.techtaurant.mainserver.link.dto.LinkCrawlBatchResponse
import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import com.techtaurant.mainserver.link.enums.LinkCrawlRunTriggerType
import com.techtaurant.mainserver.link.enums.LinkStatus
import com.techtaurant.mainserver.link.infrastructure.out.LinkCrawlBatchRepository
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.enums.UserStatus
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class LinkCrawlBatchAdminService(
    private val linkCrawlBatchRepository: LinkCrawlBatchRepository,
    private val userRepository: UserRepository,
    private val linkBatchRunService: LinkBatchRunService,
) {
    @Transactional
    fun createBatch(
        companyUserId: UUID,
        request: CreateLinkCrawlBatchRequest,
    ): LinkCrawlBatchResponse {
        val companyUser = getCompanyUser(companyUserId)
        validateCronExpression(request.cronExpression)

        val batch = CreateLinkCrawlBatchRequest.toEntity(request, companyUser)
        linkBatchRunService.validateCrawlable(batch)
        val batchId = linkCrawlBatchRepository.save(batch).id!!
        linkBatchRunService.run(batchId, LinkCrawlRunTriggerType.CREATED)

        return LinkCrawlBatchResponse.from(findBatchOrThrow(batchId))
    }

    @Transactional(readOnly = true)
    fun getBatches(companyUserId: UUID): List<LinkCrawlBatchListItemResponse> {
        getCompanyUser(companyUserId)
        return linkCrawlBatchRepository.findAllByCompanyUserId(companyUserId)
            .sortedBy { it.name }
            .map(LinkCrawlBatchListItemResponse::from)
    }

    /** 최초 수집 실행이 갱신한 lastTriggeredAt을 응답에 반영하기 위해 실행 이후 배치를 다시 읽는다. */
    private fun findBatchOrThrow(batchId: UUID): LinkCrawlBatch =
        linkCrawlBatchRepository.findById(batchId).orElseThrow {
            ApiException(LinkStatus.LINK_CRAWL_BATCH_NOT_FOUND)
        }

    private fun getCompanyUser(companyUserId: UUID): User {
        val user =
            userRepository.findById(companyUserId).orElseThrow {
                ApiException(UserStatus.COMPANY_NOT_FOUND)
            }

        if (user.role != UserRole.COMPANY) {
            throw ApiException(UserStatus.COMPANY_NOT_FOUND)
        }

        return user
    }

    private fun validateCronExpression(cronExpression: String) {
        runCatching { CronExpression.parse(cronExpression) }
            .getOrElse { throw ApiException(LinkStatus.INVALID_LINK_CRAWL_BATCH_CRON_EXPRESSION) }
    }
}
