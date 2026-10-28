package com.techtaurant.mainserver.sitemap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class SitemapEnvironmentTest {
    @Test
    fun `development starts without bucket credentials or sitemap background tasks`() {
        ApplicationContextRunner()
            .withPropertyValues("app.environment=dev")
            .withUserConfiguration(
                SitemapConfig::class.java,
                SitemapScheduler::class.java,
                SitemapStorage::class.java,
                SitemapGenerationService::class.java,
                AdminSitemapController::class.java,
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(SitemapStorage::class.java)
                assertThat(context).doesNotHaveBean(SitemapScheduler::class.java)
                assertThat(context).doesNotHaveBean(AdminSitemapController::class.java)
            }
    }
}
