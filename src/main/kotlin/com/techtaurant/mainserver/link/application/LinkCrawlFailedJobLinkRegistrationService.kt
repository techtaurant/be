package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.link.dto.RegisterFailedJobLinkRequest
import com.techtaurant.mainserver.link.enums.LinkStatus
import com.techtaurant.mainserver.link.infrastructure.out.LinkCrawlFailedJobRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 크롤러가 끝내 가져오지 못한 실패 잡의 링크를 관리자가 아티클 내용을 직접 입력해 등록한다.
 * 크롤 결과와 같은 수집 경로를 쓰므로 링크는 배치의 태그를 달고 배치를 소유한 회사에 연결되며, 그 경로가 실패 잡도 함께 닫는다.
 */
@Service
class LinkCrawlFailedJobLinkRegistrationService(
    private val linkCrawlFailedJobRepository: LinkCrawlFailedJobRepository,
    private val linkCrawlLinkCollector: LinkCrawlLinkCollector,
    private val linkCrawlFailedJobRetryService: LinkCrawlFailedJobRetryService,
) {
    @Transactional
    fun registerLink(
        failedJobId: UUID,
        request: RegisterFailedJobLinkRequest,
    ) {
        val failedJob =
            linkCrawlFailedJobRepository.findById(failedJobId).orElseThrow {
                ApiException(LinkStatus.LINK_CRAWL_FAILED_JOB_NOT_FOUND)
            }
        if (failedJob.resolvedAt != null) {
            throw ApiException(LinkStatus.LINK_CRAWL_FAILED_JOB_ALREADY_RESOLVED)
        }

        val batch = failedJob.batch
        val tagResolver = linkCrawlLinkCollector.tagResolverFor(batch)
        linkCrawlLinkCollector.collect(request.toLinkSnapshot(failedJob.articleUrl), batch, tagResolver)
        failedJob.lastRun?.let(linkCrawlFailedJobRetryService::refreshRunStatus)
    }
}
