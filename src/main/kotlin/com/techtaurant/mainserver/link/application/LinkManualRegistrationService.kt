package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.link.dto.RegisterLinkManuallyRequest
import com.techtaurant.mainserver.link.enums.LinkStatus
import com.techtaurant.mainserver.link.infrastructure.out.LinkCrawlBatchRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 크롤러가 끝내 가져오지 못한 아티클을 관리자가 직접 입력해 등록한다.
 * 크롤 결과와 같은 저장 경로를 쓰므로 링크 생성 규칙이 갈라지지 않고, 등록에 성공하면 그 URL의 실패 기록도 함께 닫힌다.
 */
@Service
class LinkManualRegistrationService(
    private val linkCrawlBatchRepository: LinkCrawlBatchRepository,
    private val linkCrawlLinkCollector: LinkCrawlLinkCollector,
) {
    @Transactional
    fun registerLink(
        batchId: UUID,
        request: RegisterLinkManuallyRequest,
    ) {
        val batch =
            linkCrawlBatchRepository.findById(batchId).orElseThrow {
                ApiException(LinkStatus.LINK_CRAWL_BATCH_NOT_FOUND)
            }

        val tagResolver = linkCrawlLinkCollector.tagResolverFor(batch)
        linkCrawlLinkCollector.collect(request.toLinkSnapshot(), batch, tagResolver)
    }
}
