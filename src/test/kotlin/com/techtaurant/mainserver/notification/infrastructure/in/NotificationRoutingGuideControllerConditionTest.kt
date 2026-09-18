package com.techtaurant.mainserver.notification.infrastructure.`in`

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

class NotificationRoutingGuideControllerConditionTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration::class.java)

    @Test
    @DisplayName("app.environment가 prod이면 알림 이동 가이드 컨트롤러를 등록하지 않는다")
    fun appEnvironmentProd_doesNotRegisterRoutingGuideController() {
        contextRunner
            .withPropertyValues("app.environment=prod")
            .run { context ->
                assertThat(context).doesNotHaveBean(NotificationRoutingGuideController::class.java)
            }
    }

    @Test
    @DisplayName("app.environment가 prod가 아니면 알림 이동 가이드 컨트롤러를 등록한다")
    fun appEnvironmentNonProd_registersRoutingGuideController() {
        contextRunner
            .withPropertyValues("app.environment=dev")
            .run { context ->
                assertThat(context).hasSingleBean(NotificationRoutingGuideController::class.java)
            }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(NotificationRoutingGuideController::class)
    private class TestConfiguration
}
