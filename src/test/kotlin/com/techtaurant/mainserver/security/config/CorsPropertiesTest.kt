package com.techtaurant.mainserver.security.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

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
    @DisplayName("전체 와일드카드 origin 패턴은 생성 시점에 거부한다")
    fun `reject bare wildcard origin pattern`() {
        // given
        val bareWildcardPattern = "*"

        // when & then
        assertThatThrownBy { CorsProperties(allowedOriginPatterns = bareWildcardPattern) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(bareWildcardPattern)
    }

    @Test
    @DisplayName("스킴만 지정한 와일드카드 origin 패턴은 생성 시점에 거부한다")
    fun `reject scheme only wildcard origin pattern`() {
        // given
        val schemeOnlyWildcardPattern = "https://techtaurant.com, https://*"

        // when & then
        assertThatThrownBy { CorsProperties(allowedOriginPatterns = schemeOnlyWildcardPattern) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("https://*")
    }

    @Test
    @DisplayName("포트 패턴이 붙은 host 와일드카드 origin 패턴도 생성 시점에 거부한다")
    fun `reject host wildcard origin pattern with port pattern`() {
        // given
        val anyPortWildcardPattern = "https://techtaurant.com, https://*:[*]"

        // when & then
        assertThatThrownBy { CorsProperties(allowedOriginPatterns = anyPortWildcardPattern) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("https://*:[*]")
    }

    @Test
    @DisplayName("포트를 지정해도 host가 와일드카드면 생성 시점에 거부한다")
    fun `reject host wildcard origin pattern with fixed port`() {
        // given
        val fixedPortWildcardPattern = "https://*:8080"

        // when & then
        assertThatThrownBy { CorsProperties(allowedOriginPatterns = fixedPortWildcardPattern) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(fixedPortWildcardPattern)
    }

    @Test
    @DisplayName("와일드카드를 반복한 host 패턴도 생성 시점에 거부한다")
    fun `reject repeated wildcard host origin pattern`() {
        // given
        val repeatedWildcardPattern = "https://**"

        // when & then
        assertThatThrownBy { CorsProperties(allowedOriginPatterns = repeatedWildcardPattern) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(repeatedWildcardPattern)
    }

    @Test
    @DisplayName("와일드카드와 구분자만으로 이뤄진 host 패턴도 생성 시점에 거부한다")
    fun `reject wildcard only host origin pattern with separator`() {
        // given
        val wildcardOnlyPattern = "https://*.*:[*]"

        // when & then
        assertThatThrownBy { CorsProperties(allowedOriginPatterns = wildcardOnlyPattern) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(wildcardOnlyPattern)
    }

    @Test
    @DisplayName("서브도메인 와일드카드는 포트 패턴이 붙어도 허용한다")
    fun `allow subdomain wildcard origin pattern with port pattern`() {
        // given
        val subdomainWildcardPattern = "https://*.techtaurant.com:[*]"

        // when
        val patterns = CorsProperties(allowedOriginPatterns = subdomainWildcardPattern).parsedAllowedOriginPatterns

        // then
        assertThat(patterns).containsExactly(subdomainWildcardPattern)
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(
        strings = [
            "https://*.com",
            "https://*.co.uk",
            "https://*.blogspot.com",
        ],
    )
    @DisplayName("public suffix 전체를 신뢰하는 서브도메인 wildcard를 거부한다")
    fun `reject wildcard origin pattern over public suffix`(publicSuffixPattern: String) {
        // when & then
        assertThatThrownBy { CorsProperties(allowedOriginPatterns = publicSuffixPattern) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(publicSuffixPattern)
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(
        strings = [
            "https://[*]",
            "https://[*]:[*]",
            "https://[*:*]",
            "https://[1*]",
            "https://[::*]",
            "https://*techtaurant.com",
            "https://techtaurant.*",
            "https://techtaurant.com*",
            "https://foo.*",
            "https://user@techtaurant.com",
            "https://techtaurant.com/path",
            "https://techtaurant.com?redirect=evil.example",
            "https://techtaurant.com#fragment",
        ],
    )
    @DisplayName("origin이 아닌 문법과 선두 단일 DNS label 외 wildcard를 거부한다")
    fun `reject invalid origin pattern grammar`(invalidPattern: String) {
        // when & then
        assertThatThrownBy { CorsProperties(allowedOriginPatterns = invalidPattern) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(invalidPattern)
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(
        strings = [
            "https://techtaurant.com",
            "http://localhost:3000",
            "https://[2001:db8::1]",
            "https://[2001:db8::1]:8443",
        ],
    )
    @DisplayName("wildcard 없는 http와 https origin은 허용한다")
    fun `allow exact http and https origin patterns`(exactOrigin: String) {
        // given
        val configuration = CorsProperties(allowedOriginPatterns = exactOrigin).createCorsConfiguration()

        // when
        val matchedOrigin = configuration.checkOrigin(exactOrigin)

        // then
        assertThat(matchedOrigin).isEqualTo(exactOrigin)
    }

    @Test
    @DisplayName("선두 단일 DNS label wildcard는 고정 포트에서도 허용한다")
    fun `allow leading dns label wildcard with fixed port`() {
        // given
        val pattern = "https://*.techtaurant.com:8443"
        val origin = "https://preview.techtaurant.com:8443"
        val configuration = CorsProperties(allowedOriginPatterns = pattern).createCorsConfiguration()

        // when
        val matchedOrigin = configuration.checkOrigin(origin)

        // then
        assertThat(matchedOrigin).isEqualTo(origin)
    }
}
