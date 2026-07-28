package com.techtaurant.mainserver.security.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

@DisplayName("RefreshTokenWhitelistPolicy 설정 바인딩")
class RefreshTokenWhitelistPolicyBindingTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration::class.java)

    @Test
    @DisplayName("설정값이 있으면 사용자당 활성 토큰 상한에 바인딩한다")
    fun validProperty_bindsPolicy() {
        contextRunner
            .withPropertyValues("security.refresh-token-whitelist.max-active-tokens-per-user=5")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(RefreshTokenWhitelistPolicy::class.java).maxActiveTokensPerUser)
                    .isEqualTo(5)
            }
    }

    @Test
    @DisplayName("사용자당 활성 토큰 상한 설정이 누락되면 시작에 실패한다")
    fun missingProperty_failsContextStartup() {
        contextRunner.run { context ->
            assertThat(context).hasFailed()
        }
    }

    @Test
    @DisplayName("사용자당 활성 토큰 상한이 양수가 아니면 시작에 실패한다")
    fun nonPositiveProperty_failsContextStartup() {
        listOf(0, -1).forEach { invalidLimit ->
            contextRunner
                .withPropertyValues(
                    "security.refresh-token-whitelist.max-active-tokens-per-user=$invalidLimit",
                )
                .run { context ->
                    assertThat(context).hasFailed()
                }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RefreshTokenWhitelistPolicy::class)
    private class TestConfiguration
}
