package com.techtaurant.mainserver.sitemap.application

import com.techtaurant.mainserver.post.dto.PostForSitemap
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.sitemap.infrastructure.out.SitemapStorage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

class SitemapServiceTest {
    private val posts = mockk<PostRepository>()
    private val storage = mockk<SitemapStorage>(relaxed = true)
    private val service = SitemapService(posts, SitemapXmlWriter("https://techtaurant.com"), storage)

    @Test
    fun `publishes a short page without another query`() {
        val page = List(1_001) { PostForSitemap(UUID(0, it.toLong() + 1), Instant.EPOCH) }
        every { posts.findPostsForSitemap(null, 50_000) } returns page
        every { storage.publishXml("sitemap/posts-1.xml", any()) } answers {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(secondArg<ByteArray>().inputStream())
            assertThat(document.getElementsByTagName("url").length).isEqualTo(1_001)
            assertThat(document.getElementsByTagName("loc").item(1_000).textContent).endsWith(page.last().id.toString())
        }

        service.generate()

        verifyOrder {
            posts.findPostsForSitemap(null, 50_000)
            storage.publishXml("sitemap/posts-1.xml", any())
            storage.publishXml("sitemap/home.xml", any())
            storage.publishXml("sitemap/index.xml", any())
        }
        verify(exactly = 1) { posts.findPostsForSitemap(any(), any()) }
    }

    @Test
    fun `uploads each completed XML before reading the next file and publishes the index last`() {
        stubPosts(50_001)
        val counts = mutableListOf<Int>()
        every { storage.publishXml(match { it.startsWith("sitemap/posts-") }, any()) } answers {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(secondArg<ByteArray>().inputStream())
            counts.add(document.getElementsByTagName("url").length)
        }
        every { storage.publishXml("sitemap/index.xml", any()) } answers {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(secondArg<ByteArray>().inputStream())
            val locations = document.getElementsByTagName("loc")
            assertThat((0 until locations.length).map { locations.item(it).textContent }).containsExactly(
                "https://techtaurant.com/sitemap/posts-1.xml",
                "https://techtaurant.com/sitemap/posts-2.xml",
                "https://techtaurant.com/sitemap/home.xml",
            )
        }

        service.generate()

        verifyOrder {
            storage.publishXml("sitemap/posts-1.xml", any())
            posts.findPostsForSitemap(UUID(0, 50_000), 50_000)
            storage.publishXml("sitemap/posts-2.xml", any())
            storage.publishXml("sitemap/home.xml", any())
            storage.publishXml("sitemap/index.xml", any())
        }
        assertThat(counts).containsExactly(50_000, 1)
    }

    @Test
    fun `second file upload failure leaves the index unchanged`() {
        stubPosts(50_001)
        every { storage.publishXml("sitemap/posts-2.xml", any()) } throws IllegalStateException("S3 unavailable")

        assertThatThrownBy { service.generate() }.isInstanceOf(IllegalStateException::class.java)

        verify(exactly = 1) { storage.publishXml("sitemap/posts-1.xml", any()) }
        verify(exactly = 0) { storage.publishXml("sitemap/home.xml", any()) }
        verify(exactly = 0) { storage.publishXml("sitemap/index.xml", any()) }
    }

    @Test
    fun `home upload failure leaves the index unchanged`() {
        every { posts.findPostsForSitemap(null, 50_000) } returns emptyList()
        every { storage.publishXml("sitemap/home.xml", any()) } throws IllegalStateException("S3 unavailable")

        assertThatThrownBy { service.generate() }.isInstanceOf(IllegalStateException::class.java)

        verify(exactly = 0) { storage.publishXml("sitemap/index.xml", any()) }
    }

    @Test
    fun `next page failure preserves the completed file and leaves the index unchanged`() {
        val chunk = List(50_000) { PostForSitemap(UUID(0, it.toLong()), Instant.EPOCH) }
        every { posts.findPostsForSitemap(null, 50_000) } returns chunk
        every { posts.findPostsForSitemap(chunk.last().id, 50_000) } throws IllegalStateException("DB unavailable")

        assertThatThrownBy { service.generate() }.isInstanceOf(IllegalStateException::class.java)

        verify(exactly = 1) { storage.publishXml("sitemap/posts-1.xml", any()) }
        verify(exactly = 0) { storage.publishXml("sitemap/posts-2.xml", any()) }
        verify(exactly = 0) { storage.publishXml("sitemap/index.xml", any()) }
    }

    @Test
    fun `empty published set publishes only home and index`() {
        every { posts.findPostsForSitemap(null, 50_000) } returns emptyList()
        every { storage.publishXml("sitemap/index.xml", any()) } answers {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(secondArg<ByteArray>().inputStream())
            val locations = document.getElementsByTagName("loc")
            assertThat(locations.length).isEqualTo(1)
            assertThat(locations.item(0).textContent).isEqualTo("https://techtaurant.com/sitemap/home.xml")
        }

        service.generate()

        verifyOrder {
            storage.publishXml("sitemap/home.xml", any())
            storage.publishXml("sitemap/index.xml", any())
        }
        verify(exactly = 0) { storage.publishXml(match { it.startsWith("sitemap/posts-") }, any()) }
    }

    @Test
    fun `exactly 50000 posts checks the next cursor without publishing an extra file`() {
        stubPosts(50_000)

        service.generate()

        verifyOrder {
            posts.findPostsForSitemap(null, 50_000)
            storage.publishXml("sitemap/posts-1.xml", any())
            posts.findPostsForSitemap(UUID(0, 50_000), 50_000)
            storage.publishXml("sitemap/home.xml", any())
            storage.publishXml("sitemap/index.xml", any())
        }
        verify(exactly = 2) { posts.findPostsForSitemap(any(), any()) }
        verify(exactly = 0) { storage.publishXml("sitemap/posts-2.xml", any()) }
    }

    @ParameterizedTest
    @CsvSource("0, 0", "1, 1", "50000, 1", "50001, 2", "100000, 2", "100001, 3")
    fun `splits pages without dropping posts or creating an extra empty file`(
        total: Long,
        expectedFiles: Int,
    ) {
        stubPosts(total)
        var uploadedPosts = 0L
        var uploadedFiles = 0
        every { storage.publishXml(match { it.startsWith("sitemap/posts-") }, any()) } answers {
            val bytes = secondArg<ByteArray>()
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(bytes.inputStream())
            val locations = document.getElementsByTagName("loc")
            assertThat(locations.length).isBetween(1, 50_000)
            for (index in 0 until locations.length) {
                assertThat(locations.item(index).textContent)
                    .isEqualTo("https://techtaurant.com/posts/${UUID(0, uploadedPosts + index + 1)}")
            }
            uploadedPosts += locations.length
            uploadedFiles++
            assertThat(firstArg<String>()).isEqualTo("sitemap/posts-$uploadedFiles.xml")
            assertThat(bytes.size).isLessThan(SitemapXmlWriter.MAX_BYTES)
        }

        service.generate()

        assertThat(uploadedPosts).isEqualTo(total)
        assertThat(uploadedFiles).isEqualTo(expectedFiles)
    }

    private fun stubPosts(total: Long) {
        every { posts.findPostsForSitemap(any(), 50_000) } answers {
            val after = firstArg<UUID?>()?.leastSignificantBits ?: 0
            (after + 1..minOf(after + 50_000, total)).map { PostForSitemap(UUID(0, it), Instant.EPOCH) }
        }
    }
}
