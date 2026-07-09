package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.link.dto.CreateLinkCrawlBatchRequest
import com.techtaurant.mainserver.link.dto.LinkCrawlBatchListItemResponse
import com.techtaurant.mainserver.link.dto.LinkCrawlBatchResponse
import com.techtaurant.mainserver.link.dto.UpdateLinkCrawlBatchRequest
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
        validatePageRange(request.startPage, request.endPage)

        val batch =
            LinkCrawlBatch(
                companyUser = companyUser,
                name = request.name.trim(),
                baseUrl = request.baseUrl.trim(),
                pageUriTemplate = request.pageUriTemplate.trim(),
                itemSelector = request.itemSelector.trim(),
                articleLinkSelector = request.articleLinkSelector.trim(),
                titleSelector = request.titleSelector.trim(),
                summarySelector = request.summarySelector?.trim()?.takeIf { it.isNotEmpty() },
                createdAtSelectors = normalizeLines(request.createdAtSelectors),
                tagNames = normalizeLines(request.tagNames),
                cronExpression = request.cronExpression.trim(),
                startPage = request.startPage,
                endPage = request.endPage,
                active = request.active,
            )
        linkBatchRunService.validateCrawlable(batch)
        val savedBatch = linkCrawlBatchRepository.save(batch)
        val savedBatchId = savedBatch.id ?: throw ApiException(LinkStatus.LINK_CRAWL_BATCH_NOT_FOUND)
        linkBatchRunService.run(savedBatchId, LinkCrawlRunTriggerType.MANUAL)

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

        request.name?.let { batch.name = it.trim() }
        request.baseUrl?.let { batch.baseUrl = it.trim() }
        request.pageUriTemplate?.let { batch.pageUriTemplate = it.trim() }
        request.itemSelector?.let { batch.itemSelector = it.trim() }
        request.articleLinkSelector?.let { batch.articleLinkSelector = it.trim() }
        request.titleSelector?.let { batch.titleSelector = it.trim() }
        request.summarySelector?.let { batch.summarySelector = it.trim().takeIf(String::isNotEmpty) }
        request.createdAtSelectors?.let { batch.createdAtSelectors = normalizeLines(it) }
        request.tagNames?.let { batch.tagNames = normalizeLines(it) }
        request.cronExpression?.let {
            validateCronExpression(it)
            batch.cronExpression = it.trim()
        }

        request.startPage?.let { batch.startPage = it }
        request.endPage?.let { batch.endPage = it }
        request.active?.let { batch.active = it }

        validatePageRange(batch.startPage, batch.endPage)
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

    private fun validatePageRange(
        startPage: Int,
        endPage: Int,
    ) {
        if (endPage < startPage) {
            throw ApiException(LinkStatus.INVALID_LINK_CRAWL_BATCH_PAGE_RANGE)
        }
    }

    private fun normalizeLines(values: List<String>): String? {
        return values.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
            .takeIf(List<String>::isNotEmpty)
            ?.joinToString("\n")
    }
}
