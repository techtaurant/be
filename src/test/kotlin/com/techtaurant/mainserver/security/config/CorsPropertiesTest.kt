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
}
