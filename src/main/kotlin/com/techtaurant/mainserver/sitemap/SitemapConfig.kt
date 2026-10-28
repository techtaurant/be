package com.techtaurant.mainserver.sitemap

import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("sitemap.enabled", havingValue = "true")
class SitemapConfig {
    // Keep Boot's default scheduler for existing link/cleanup tasks.
    @Bean(defaultCandidate = false)
    fun sitemapTaskScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 1
            setThreadNamePrefix("sitemap-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(150)
            setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
            setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        }

    @Bean
    fun sitemapLockProvider(dataSource: DataSource): LockProvider =
        JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(JdbcTemplate(dataSource))
                .usingDbTime()
                .build(),
        )
}
