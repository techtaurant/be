package com.techtaurant.mainserver.security.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CorsPropertiesTest {
    private val corsConfiguration =
        CorsProperties(
            allowedOriginPatterns = "https://techtaurant.com, https://*.techtaurant.com",
        ).createCorsConfiguration()

    @Test
    @DisplayName("허용 패턴과 일치하는 서브도메인 origin을 반환한다")
    fun `allow origin matching subdomain pattern`() {
        // given
        val origin = "https://preview.techtaurant.com"

        // when
        val matchedOrigin = corsConfiguration.checkOrigin(origin)

        // then
        assertThat(matchedOrigin).isEqualTo(origin)
    }

    @Test
    @DisplayName("허용 패턴과 일치하지 않는 origin은 거부한다")
    fun `reject origin not matching allowed pattern`() {
        // given
        val origin = "https://evil.example"

        // when
        val matchedOrigin = corsConfiguration.checkOrigin(origin)

        // then
        assertThat(matchedOrigin).isNull()
    }

    @Test
    @DisplayName("포트 목록 내부의 쉼표를 origin 구분자로 처리하지 않는다")
    fun `preserve commas inside origin pattern port list`() {
        // given
        val corsProperties =
            CorsProperties(
                allowedOriginPatterns =
                    "https://*.techtaurant.com:[8080,8081], https://techtaurant.com",
            )

        // when
        val parsedAllowedOriginPatterns = corsProperties.parsedAllowedOriginPatterns

        // then
        assertThat(parsedAllowedOriginPatterns).containsExactly(
            "https://*.techtaurant.com:[8080,8081]",
            "https://techtaurant.com",
        )
    }

    @Test
    @DisplayName("허용 패턴의 포트 목록에 포함된 모든 origin을 허용한다")
    fun `allow origins matching ports in origin pattern port list`() {
        // given
        val corsConfiguration =
            CorsProperties(
                allowedOriginPatterns = "https://*.techtaurant.com:[8080,8081]",
            ).createCorsConfiguration()
        val origins =
            listOf(
                "https://preview.techtaurant.com:8080",
                "https://preview.techtaurant.com:8081",
            )

        // when
        val matchedOrigins = origins.map(corsConfiguration::checkOrigin)

        // then
        assertThat(matchedOrigins).containsExactlyElementsOf(origins)
    }
}
