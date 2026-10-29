package com.techtaurant.mainserver.sitemap

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

/** 기존 예약 작업과 분리된 사이트맵 실행 스레드를 구성합니다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("app.environment", havingValue = "prod")
class SitemapConfig {
    /**
     * 기존 링크 수집·정리 작업의 기본 스케줄러와 별도로 실행 스레드를 둡니다.
     * 애플리케이션 정상 종료 시 이미 실행 중인 작업을 기다리되, 새 예약 작업은 실행하지 않습니다.
     */
    @Bean(defaultCandidate = false)
    fun sitemapTaskScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 1
            setThreadNamePrefix("sitemap-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(SHUTDOWN_WAIT_SECONDS)
            setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
            setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        }

    private companion object {
        const val SHUTDOWN_WAIT_SECONDS = 150
    }
}
