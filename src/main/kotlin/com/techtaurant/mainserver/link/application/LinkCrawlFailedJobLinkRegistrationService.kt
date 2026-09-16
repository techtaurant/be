package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.link.dto.RegisterFailedJobLinkRequest
import com.techtaurant.mainserver.link.enums.LinkStatus
import com.techtaurant.mainserver.link.infrastructure.out.LinkCrawlFailedJobRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 크롤러가 끝내 가져오지 못한 실패 잡의 링크를 관리자가 아티클 내용을 직접 입력해 등록한다.
 * 크롤 결과와 같은 수집 경로를 쓰므로 링크는 배치의 태그를 달고 배치를 소유한 회사에 연결된다.
 */
@Service
class LinkCrawlFailedJobLinkRegistrationService(
    private val linkCrawlFailedJobRepository: LinkCrawlFailedJobRepository,
    private val linkCrawlLinkCollector: LinkCrawlLinkCollector,
) {
    /**
     * 링크를 저장하기 전에 실패 잡을 조건부로 먼저 해소한다.
     * 같은 잡에 등록 요청이 겹치면 행 잠금 뒤 한 요청만 해소에 성공하고, 나머지는 409로 롤백되어 먼저 등록한 링크 내용을 덮어쓰지 않는다.
     */
    @Transactional
    fun registerLink(
        failedJobId: UUID,
        request: RegisterFailedJobLinkRequest,
    ) {
        val failedJob =
            linkCrawlFailedJobRepository.findById(failedJobId).orElseThrow {
                ApiException(LinkStatus.LINK_CRAWL_FAILED_JOB_NOT_FOUND)
            }
        val batch = failedJob.batch
        val isResolvedByThisRequest =
            linkCrawlFailedJobRepository.markResolvedIfUnresolved(requireNotNull(batch.id), failedJob.articleUrl, Instant.now())
        if (!isResolvedByThisRequest) {
            throw ApiException(LinkStatus.LINK_CRAWL_FAILED_JOB_ALREADY_RESOLVED)
        }

        val tagResolver = linkCrawlLinkCollector.tagResolverFor(batch)
        linkCrawlLinkCollector.saveLinkAndResolveFailedJob(request.toLinkSnapshot(failedJob.articleUrl), batch, tagResolver)
    }
}
