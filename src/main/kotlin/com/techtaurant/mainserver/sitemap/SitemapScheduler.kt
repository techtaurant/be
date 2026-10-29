package com.techtaurant.mainserver.sitemap

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.common.lock.LockStatus
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty("app.environment", havingValue = "prod")
class SitemapScheduler(private val service: SitemapGenerationService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul", scheduler = "sitemapTaskScheduler")
    fun generate() {
        try {
            service.generate()
        } catch (exception: ApiException) {
            if (exception.status != LockStatus.LOCK_ACQUISITION_FAILED) throw exception
            log.info("Skipped sitemap generation: another invocation holds the lease")
        }
    }

    @Scheduled(fixedDelay = FRESHNESS_REFRESH_INTERVAL_MILLIS, scheduler = "sitemapTaskScheduler")
    fun refreshPublishedTimestamp() {
        service.refreshPublishedTimestamp()
    }

    private companion object {
        const val FRESHNESS_REFRESH_INTERVAL_MILLIS = 300_000L
    }
}
