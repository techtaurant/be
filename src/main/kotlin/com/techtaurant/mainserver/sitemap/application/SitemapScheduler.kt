package com.techtaurant.mainserver.sitemap.application

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class SitemapScheduler(
    private val sitemapService: SitemapService,
) {
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "sitemap-publication", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    fun generate() {
        sitemapService.generate()
    }
}
