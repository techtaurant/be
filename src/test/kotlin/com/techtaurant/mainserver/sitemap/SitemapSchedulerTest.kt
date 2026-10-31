package com.techtaurant.mainserver.sitemap

import com.techtaurant.mainserver.post.dto.PostForSitemap
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

class SitemapSchedulerTest {
    private val posts = mockk<PostRepository>()
    private val storage = mockk<SitemapStorage>(relaxed = true)
    private val scheduler = SitemapScheduler(posts, SitemapXmlWriter(), storage)

    @Test
    fun `reads successive chunks and uploads the file before index then deletes the temporary file`() {
        val first = List(1_000) { PostForSitemap(UUID(0, it.toLong() + 1), Instant.EPOCH) }
        val last = PostForSitemap(UUID(0, 1_001), Instant.EPOCH)
        every { posts.findPostsForSitemap(null, 1_000) } returns first
        every { posts.findPostsForSitemap(first.last().id, 1_000) } returns listOf(last)
        var uploadedFile: Path? = null
        every { storage.publishFile("sitemap/posts-1.xml", any()) } answers {
            uploadedFile = secondArg<Path>()
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(uploadedFile!!.toFile())
            assertThat(document.getElementsByTagName("url").length).isEqualTo(1_001)
            assertThat(document.getElementsByTagName("loc").item(1_000).textContent).endsWith(last.id.toString())
        }

        scheduler.generate()

        verifyOrder {
            posts.findPostsForSitemap(null, 1_000)
            posts.findPostsForSitemap(first.last().id, 1_000)
            storage.publishFile("sitemap/posts-1.xml", any())
            storage.publish(SitemapStorage.HOME_KEY, any())
            storage.publish(SitemapStorage.INDEX_KEY, any())
        }
        verify(exactly = 2) { posts.findPostsForSitemap(any(), any()) }
        assertThat(Files.exists(uploadedFile!!)).isFalse()
    }

    @Test
    fun `publishes all numbered files before an index containing every file`() {
        every { posts.findPostsForSitemap(any(), 1_000) } answers {
            val after = firstArg<UUID?>()?.leastSignificantBits ?: 0
            (after + 1..minOf(after + 1_000, 50_001)).map { PostForSitemap(UUID(0, it), Instant.EPOCH) }
        }
        val uploadedFiles = mutableListOf<Path>()
        val counts = mutableListOf<Int>()
        every { storage.publishFile(any(), any()) } answers {
            val file = secondArg<Path>()
            uploadedFiles.add(file)
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile())
            counts.add(document.getElementsByTagName("url").length)
        }
        every { storage.publish(SitemapStorage.INDEX_KEY, any()) } answers {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(secondArg<ByteArray>().inputStream())
            val locations = document.getElementsByTagName("loc")
            assertThat((0 until locations.length).map { locations.item(it).textContent }).containsExactly(
                "https://techtaurant.com/sitemap/home.xml",
                "https://techtaurant.com/sitemap/posts-1.xml",
                "https://techtaurant.com/sitemap/posts-2.xml",
            )
        }

        scheduler.generate()

        verifyOrder {
            storage.publishFile("sitemap/posts-1.xml", any())
            storage.publishFile("sitemap/posts-2.xml", any())
            storage.publish(SitemapStorage.HOME_KEY, any())
            storage.publish(SitemapStorage.INDEX_KEY, any())
        }
        assertThat(counts).containsExactly(50_000, 1)
        assertThat(uploadedFiles).allMatch { !Files.exists(it) && !Files.exists(it.parent) }
    }

    @Test
    fun `second file upload failure leaves the index unchanged and removes all temporary files`() {
        every { posts.findPostsForSitemap(any(), 1_000) } answers {
            val after = firstArg<UUID?>()?.leastSignificantBits ?: 0
            (after + 1..minOf(after + 1_000, 50_001)).map { PostForSitemap(UUID(0, it), Instant.EPOCH) }
        }
        val files = mutableListOf<Path>()
        every { storage.publishFile(any(), any()) } answers {
            files.add(secondArg())
            if (firstArg<String>() == "sitemap/posts-2.xml") {
                throw IllegalStateException("S3 unavailable")
            }
        }

        assertThatThrownBy { scheduler.generate() }.isInstanceOf(IllegalStateException::class.java)

        verify(exactly = 0) { storage.publish(any(), any()) }
        assertThat(files).hasSize(2).allMatch { !Files.exists(it) && !Files.exists(it.parent) }
    }

    @Test
    fun `home upload failure leaves the index unchanged`() {
        every { posts.findPostsForSitemap(null, 1_000) } returns emptyList()
        every { storage.publish(SitemapStorage.HOME_KEY, any()) } throws IllegalStateException("S3 unavailable")

        assertThatThrownBy { scheduler.generate() }.isInstanceOf(IllegalStateException::class.java)

        verify(exactly = 0) { storage.publish(SitemapStorage.INDEX_KEY, any()) }
    }

    @Test
    fun `later chunk failure does not publish a partial sitemap`() {
        val chunk = List(1_000) { PostForSitemap(UUID(0, it.toLong()), Instant.EPOCH) }
        every { posts.findPostsForSitemap(null, 1_000) } returns chunk
        every { posts.findPostsForSitemap(chunk.last().id, 1_000) } throws IllegalStateException("DB unavailable")
        assertThatThrownBy { scheduler.generate() }.isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 0) { storage.publishFile(any(), any()) }
        verify(exactly = 0) { storage.publish(any(), any()) }
    }

    @Test
    fun `posts upload failure preserves the index and cleans up the temporary file`() {
        every { posts.findPostsForSitemap(null, 1_000) } returns emptyList()
        var uploadedFile: Path? = null
        every { storage.publishFile(any(), any()) } answers {
            uploadedFile = secondArg()
            throw IllegalStateException("S3 unavailable")
        }
        assertThatThrownBy { scheduler.generate() }.isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 0) { storage.publish(any(), any()) }
        assertThat(Files.exists(uploadedFile!!)).isFalse()
    }
}
