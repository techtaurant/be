package com.techtaurant.mainserver.sitemap

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

class SitemapXmlWriterTest {
    private val writer = SitemapXmlWriter()

    @Test
    fun `renders canonical URLs and actual update timestamps as valid namespaced XML`() {
        val post = SitemapPost(UUID.randomUUID(), Instant.parse("2026-10-20T12:34:56Z"))
        val xml = writer.write(listOf(post), "https://techtaurant.com/")
        val document =
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(xml.inputStream())
        assertThat(document.documentElement.namespaceURI).isEqualTo("http://www.sitemaps.org/schemas/sitemap/0.9")
        assertThat(document.getElementsByTagName("loc").item(0).textContent)
            .isEqualTo("https://techtaurant.com/posts/${post.id}")
        assertThat(document.getElementsByTagName("lastmod").item(0).textContent).isEqualTo(post.updatedAt.toString())
    }

    @Test
    fun `escapes XML text and accepts an empty published set`() {
        val post = SitemapPost(UUID.randomUUID(), Instant.EPOCH)
        val xml = writer.write(listOf(post), "https://example.com/a&b")
        assertThat(String(xml)).contains("a&amp;b")
        val empty = writer.write(emptyList(), "https://techtaurant.com")
        assertThat(String(empty)).contains("urlset").doesNotContain("<url>")
    }

    @Test
    fun `allows 50000 URLs but refuses 50001 instead of truncating`() {
        val posts = List(50_001) { SitemapPost(UUID(0, it.toLong()), Instant.EPOCH) }
        assertThat(writer.write(posts.take(50_000), "https://techtaurant.com").size).isLessThan(SitemapXmlWriter.MAX_BYTES)
        assertThatThrownBy { writer.write(posts, "https://techtaurant.com") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects duplicate URLs and files exceeding the byte limit`() {
        val post = SitemapPost(UUID.randomUUID(), Instant.EPOCH)
        assertThatThrownBy { writer.write(listOf(post, post), "https://techtaurant.com") }
            .isInstanceOf(IllegalArgumentException::class.java)
        val posts = List(55) { SitemapPost(UUID(0, it.toLong()), Instant.EPOCH) }
        assertThatThrownBy { writer.write(posts, "https://example.com/" + "x".repeat(1_000_000)) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("50 MiB")
    }

    @Test
    fun `enabled configuration requires a bucket and a canonical HTTPS origin`() {
        assertThatThrownBy { SitemapProperties(enabled = true) }.isInstanceOf(IllegalArgumentException::class.java)
        listOf("http://example.com", "https://example.com/posts", "https://user@example.com", "https://example.com?q=1").forEach {
            assertThatThrownBy { SitemapProperties(true, "test", it) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(SitemapProperties().enabled).isFalse()
    }
}
