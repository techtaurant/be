package com.techtaurant.mainserver.sitemap.application

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.common.lock.LockStatus
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 사이트맵 정기 생성과 게시 시각 지표 갱신을 예약합니다. */
@Component
class SitemapScheduler(
    private val service: SitemapGenerationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 매일 04:00 KST에 생성하며, 다른 실행이 잠금을 보유한 경우 이번 실행을 건너뜁니다. */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul", scheduler = "sitemapTaskScheduler")
    fun generate() {
        try {
            service.generate()
        } catch (exception: ApiException) {
            if (exception.status != LockStatus.LOCK_ACQUISITION_FAILED) throw exception
            log.info("Skipped sitemap generation: another invocation holds the lease")
        }
    }

    /** 이전 조회 완료 후 5분마다 S3의 게시 시각을 다시 확인합니다. */
    @Scheduled(fixedDelay = FRESHNESS_REFRESH_INTERVAL_MILLIS, scheduler = "sitemapTaskScheduler")
    fun refreshPublishedTimestamp() {
        service.refreshPublishedTimestamp()
    }

    private companion object {
        const val FRESHNESS_REFRESH_INTERVAL_MILLIS = 300_000L
    }
}
