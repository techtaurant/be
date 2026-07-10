package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.link.dto.CreateLinkCrawlBatchRequest
import com.techtaurant.mainserver.link.dto.LinkCrawlBatchListItemResponse
import com.techtaurant.mainserver.link.dto.LinkCrawlBatchResponse
import com.techtaurant.mainserver.link.dto.UpdateLinkCrawlBatchRequest
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

        val batch = LinkCrawlBatchMapper.toEntity(request, companyUser)
        linkBatchRunService.validateCrawlable(batch)
        val savedBatch = linkCrawlBatchRepository.save(batch)
        linkBatchRunService.run(savedBatch.id!!, LinkCrawlRunTriggerType.CREATED)

        return LinkCrawlBatchResponse.from(savedBatch)
    }

    @Transactional(readOnly = true)
    fun getBatches(companyUserId: UUID): List<LinkCrawlBatchListItemResponse> {
        getCompanyUser(companyUserId)
        return linkCrawlBatchRepository.findAllByCompanyUserId(companyUserId)
            .sortedBy { it.name }
            .map(LinkCrawlBatchListItemResponse::from)
    }

    @Transactional
    fun updateBatch(
        batchId: UUID,
        request: UpdateLinkCrawlBatchRequest,
    ): LinkCrawlBatchResponse {
        val batch =
            linkCrawlBatchRepository.findById(batchId).orElseThrow {
                ApiException(LinkStatus.LINK_CRAWL_BATCH_NOT_FOUND)
            }

        request.cronExpression?.let {
            validateCronExpression(it)
        }
        request.applyTo(batch)
        linkBatchRunService.validateCrawlable(batch)

        return LinkCrawlBatchResponse.from(batch)
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
