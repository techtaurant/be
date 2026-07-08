package com.techtaurant.mainserver.link.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

@DisplayName("JsoupLinkDocumentFetcher 테스트")
class JsoupLinkDocumentFetcherTest {
    private val playwrightLinkDocumentFetcher: LinkDocumentFetcher = mockk()
    private val fetcher = JsoupLinkDocumentFetcher(playwrightLinkDocumentFetcher)

    @Test
    @DisplayName("Jsoup 요청이 실패하면 Playwright로 렌더링 문서를 가져온다")
    fun fetchFallsBackToPlaywrightWhenJsoupFails() {
        // Given
        val url = "not-a-url"
        val fallbackDocument =
            Jsoup.parse(
                """
                <html>
                  <body>
                    <main>CSR 렌더링</main>
                  </body>
                </html>
                """.trimIndent(),
                url,
            )
        every { playwrightLinkDocumentFetcher.fetch(url) } returns fallbackDocument

        // When
        val result = fetcher.fetch(url)

        // Then
        assertThat(result.selectFirst("main")?.text()).isEqualTo("CSR 렌더링")
        verify(exactly = 1) { playwrightLinkDocumentFetcher.fetch(url) }
    }

    @Test
    @DisplayName("Playwright fallback도 실패하면 Jsoup 실패 원인을 suppressed exception으로 보존한다")
    fun fetchPreservesJsoupFailureWhenPlaywrightFails() {
        // Given
        val url = "not-a-url"
        val playwrightException = IllegalStateException("playwright failure")
        every { playwrightLinkDocumentFetcher.fetch(url) } throws playwrightException

        // When
        val result =
            assertFailsWith<IllegalStateException> {
                fetcher.fetch(url)
            }

        // Then
        assertThat(result).isSameAs(playwrightException)
        assertThat(result.suppressed)
            .hasSize(1)
            .allSatisfy { suppressedException ->
                assertThat(suppressedException).isInstanceOf(IllegalArgumentException::class.java)
            }
    }
}
