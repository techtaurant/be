package com.techtaurant.mainserver.security.infrastructure.`in`

import com.techtaurant.mainserver.security.cache.TokenCachePort
import com.techtaurant.mainserver.security.helper.CookieHelper
import com.techtaurant.mainserver.security.jwt.JwtProperties
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.security.service.DevTestAuthService
import com.techtaurant.mainserver.user.application.UserUniqueNameService
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

class DevTestAuthControllerConditionTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration::class.java)

    @Test
    @DisplayName("app.environment가 prod이면 개발 테스트 인증 빈을 등록하지 않는다")
    fun appEnvironmentProd_doesNotRegisterDevTestAuthBeans() {
        contextRunner
            .withPropertyValues("app.environment=prod")
            .run { context ->
                assertThat(context).doesNotHaveBean(DevTestAuthController::class.java)
                assertThat(context).doesNotHaveBean(DevTestAuthService::class.java)
            }
    }

    @Test
    @DisplayName("app.environment가 prod가 아니면 개발 테스트 인증 빈을 등록한다")
    fun appEnvironmentNonProd_registersDevTestAuthBeans() {
        contextRunner
            .withPropertyValues("app.environment=dev")
            .run { context ->
                assertThat(context).hasSingleBean(DevTestAuthController::class.java)
                assertThat(context).hasSingleBean(DevTestAuthService::class.java)
            }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(DevTestAuthController::class, DevTestAuthService::class)
    private class TestConfiguration {
        @Bean
        fun userRepository(): UserRepository = mockk(relaxed = true)

        @Bean
        fun jwtTokenProvider(): JwtTokenProvider = mockk(relaxed = true)

        @Bean
        fun jwtProperties(): JwtProperties {
            return JwtProperties(
                secret = "test-secret",
                accessTokenExpireMs = 3600000,
                refreshTokenExpireMs = 604800000,
            )
        }

        @Bean
        fun cookieHelper(): CookieHelper = mockk(relaxed = true)

        @Bean
        fun tokenCacheManager(): TokenCachePort = mockk(relaxed = true)

        @Bean
        fun userUniqueNameService(): UserUniqueNameService = mockk(relaxed = true)
    }
}
