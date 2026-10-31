package com.techtaurant.mainserver.sitemap

import com.techtaurant.mainserver.post.dto.PostForSitemap
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

class SitemapXmlWriterTest {
    private val writer = SitemapXmlWriter("https://techtaurant.com")

    @TempDir lateinit var tempDir: Path

    private fun writePosts(posts: List<PostForSitemap>): ByteArray {
        val file = tempDir.resolve("posts.xml")
        writer.writePostsXml(posts.iterator(), file)
        return Files.readAllBytes(file)
    }

    @Test
    fun `renders canonical URLs and actual update timestamps as valid namespaced XML`() {
        val post = PostForSitemap(UUID.randomUUID(), Instant.parse("2026-10-20T12:34:56Z"))
        val xml = writePosts(listOf(post))
        val document =
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(xml.inputStream())
        assertThat(document.documentElement.namespaceURI).isEqualTo("http://www.sitemaps.org/schemas/sitemap/0.9")
        assertThat(document.getElementsByTagName("loc").item(0).textContent)
            .isEqualTo("https://techtaurant.com/posts/${post.id}")
        assertThat(document.getElementsByTagName("lastmod").item(0).textContent).isEqualTo(post.updatedAt.toString())
    }

    @Test
    fun `index links to home and all public posts sitemaps with the sitemap namespace`() {
        val document =
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(writer.writeIndexXml(2).inputStream())
        assertThat(document.documentElement.localName).isEqualTo("sitemapindex")
        assertThat(document.documentElement.namespaceURI).isEqualTo("http://www.sitemaps.org/schemas/sitemap/0.9")
        assertThat(document.getElementsByTagName("sitemap").length).isEqualTo(3)
        assertThat(document.getElementsByTagName("loc").item(0).textContent)
            .isEqualTo("https://techtaurant.com/sitemap/home.xml")
        assertThat(document.getElementsByTagName("loc").item(1).textContent)
            .isEqualTo("https://techtaurant.com/sitemap/posts-1.xml")
        assertThat(document.getElementsByTagName("loc").item(2).textContent)
            .isEqualTo("https://techtaurant.com/sitemap/posts-2.xml")
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
        assertThat(String(writePosts(emptyList()))).contains("urlset").doesNotContain("<url>")
    }

    @ParameterizedTest
    @CsvSource("0, 1", "1, 1", "50000, 1", "50001, 2", "100000, 2", "100001, 3")
    fun `splits at 50000 without dropping posts or creating an extra empty file`(
        total: Int,
        expectedFiles: Int,
    ) {
        val posts = (1..total).asSequence().map { PostForSitemap(UUID(0, it.toLong()), Instant.EPOCH) }.iterator()
        val counts = mutableListOf<Int>()
        do {
            val file = tempDir.resolve("posts-${counts.size + 1}.xml")
            counts.add(writer.writePostsXml(posts, file))
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile())
            val locations = document.getElementsByTagName("loc")
            assertThat(locations.length).isEqualTo(counts.last())
            val previousCount = counts.sum() - counts.last()
            for (index in 0 until locations.length) {
                assertThat(locations.item(index).textContent)
                    .isEqualTo("https://techtaurant.com/posts/${UUID(0, (previousCount + index + 1).toLong())}")
            }
            assertThat(Files.size(file)).isLessThan(SitemapXmlWriter.MAX_BYTES.toLong())
        } while (posts.hasNext())
        assertThat(counts).hasSize(expectedFiles)
        assertThat(counts.sum()).isEqualTo(total)
        assertThat(counts).allMatch { it <= 50_000 }
    }
}
