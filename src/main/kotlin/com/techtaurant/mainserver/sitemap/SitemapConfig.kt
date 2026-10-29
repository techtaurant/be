package com.techtaurant.mainserver.sitemap

import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import javax.sql.DataSource

/** 사이트맵 실행 스레드와 여러 서버가 공유할 DB 잠금을 구성합니다. */
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

    /** 서버별 시계 차이의 영향을 줄이도록 DB 시간을 기준으로 잠금 만료를 판단합니다. */
    @Bean
    fun sitemapLockProvider(dataSource: DataSource): LockProvider =
        JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(JdbcTemplate(dataSource))
                .usingDbTime()
                .build(),
        )

    private companion object {
        const val SHUTDOWN_WAIT_SECONDS = 150
    }
}
