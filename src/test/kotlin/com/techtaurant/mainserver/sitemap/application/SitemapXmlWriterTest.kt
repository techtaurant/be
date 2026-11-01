package com.techtaurant.mainserver.sitemap.application

import com.techtaurant.mainserver.post.dto.PostForSitemap
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

class SitemapXmlWriterTest {
    private val writer = SitemapXmlWriter("https://techtaurant.com")

    @Test
    fun `renders canonical URLs and actual update timestamps as valid namespaced XML`() {
        val post = PostForSitemap(UUID.randomUUID(), Instant.parse("2026-10-20T12:34:56Z"))
        val xml = writer.writePostsXml(listOf(post))
        val document =
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(xml.inputStream())
        assertThat(document.documentElement.namespaceURI).isEqualTo("http://www.sitemaps.org/schemas/sitemap/0.9")
        assertThat(document.getElementsByTagName("loc").item(0).textContent)
            .isEqualTo("https://techtaurant.com/posts/${post.id}")
        assertThat(document.getElementsByTagName("lastmod").item(0).textContent).isEqualTo(post.updatedAt.toString())
    }

    @Test
    fun `index uses the supplied paths in order regardless of sitemap type`() {
        val paths = listOf("sitemap/home.xml", "sitemap/posts-1.xml", "sitemap/users-1.xml")
        val document =
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(writer.writeIndexXml(paths).inputStream())
        assertThat(document.documentElement.localName).isEqualTo("sitemapindex")
        assertThat(document.documentElement.namespaceURI).isEqualTo("http://www.sitemaps.org/schemas/sitemap/0.9")
        assertThat(document.getElementsByTagName("sitemap").length).isEqualTo(3)
        assertThat(document.getElementsByTagName("loc").item(0).textContent)
            .isEqualTo("https://techtaurant.com/sitemap/home.xml")
        assertThat(document.getElementsByTagName("loc").item(1).textContent)
            .isEqualTo("https://techtaurant.com/sitemap/posts-1.xml")
        assertThat(document.getElementsByTagName("loc").item(2).textContent)
            .isEqualTo("https://techtaurant.com/sitemap/users-1.xml")
        assertThat(document.getElementsByTagName("lastmod").length).isZero()
    }

    @Test
    fun `home contains only the canonical listing URL without invented modification time`() {
        val document =
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(writer.writeHomeXml().inputStream())
        assertThat(document.documentElement.localName).isEqualTo("urlset")
        assertThat(document.documentElement.namespaceURI).isEqualTo("http://www.sitemaps.org/schemas/sitemap/0.9")
        assertThat(document.getElementsByTagName("url").length).isEqualTo(1)
        assertThat(document.getElementsByTagName("loc").item(0).textContent).isEqualTo("https://techtaurant.com/posts")
        assertThat(document.getElementsByTagName("lastmod").length).isZero()
    }

    @Test
    fun `accepts an empty published set`() {
        assertThat(String(writer.writePostsXml(emptyList()))).contains("urlset").doesNotContain("<url>")
    }
}
