package com.techtaurant.mainserver.sitemap

import com.techtaurant.mainserver.sitemap.application.SitemapXmlWriter
import com.techtaurant.mainserver.sitemap.dto.SitemapPost
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
        val xml = writer.writePosts(listOf(post))
        val document =
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(xml.inputStream())
        assertThat(document.documentElement.namespaceURI).isEqualTo("http://www.sitemaps.org/schemas/sitemap/0.9")
        assertThat(document.getElementsByTagName("loc").item(0).textContent)
            .isEqualTo("https://techtaurant.com/posts/${post.id}")
        assertThat(document.getElementsByTagName("lastmod").item(0).textContent).isEqualTo(post.updatedAt.toString())
    }

    @Test
    fun `index links to the public posts sitemap with the sitemap namespace`() {
        val document =
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(writer.writeIndex().inputStream())
        assertThat(document.documentElement.localName).isEqualTo("sitemapindex")
        assertThat(document.documentElement.namespaceURI).isEqualTo("http://www.sitemaps.org/schemas/sitemap/0.9")
        assertThat(document.getElementsByTagName("sitemap").length).isEqualTo(1)
        assertThat(document.getElementsByTagName("loc").item(0).textContent)
            .isEqualTo("https://techtaurant.com/sitemap/posts.xml")
        assertThat(document.getElementsByTagName("lastmod").length).isZero()
    }

    @Test
    fun `accepts an empty published set`() {
        assertThat(String(writer.writePosts(emptyList()))).contains("urlset").doesNotContain("<url>")
    }

    @Test
    fun `allows 50000 URLs but refuses 50001 instead of truncating`() {
        val posts = List(50_001) { SitemapPost(UUID(0, it.toLong()), Instant.EPOCH) }
        assertThat(writer.writePosts(posts.take(50_000)).size).isLessThan(SitemapXmlWriter.MAX_BYTES)
        assertThatThrownBy { writer.writePosts(posts) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
