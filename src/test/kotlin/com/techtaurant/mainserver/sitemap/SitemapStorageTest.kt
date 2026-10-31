package com.techtaurant.mainserver.sitemap

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import java.nio.file.Files
import java.nio.file.Path

class SitemapStorageTest {
    @TempDir lateinit var tempDir: Path

    private val s3 = mockk<S3Client>()
    private val storage = SitemapStorage(s3, "isolated-test-bucket")

    @Test
    fun `uploads XML to the configured public bucket`() {
        val request = slot<PutObjectRequest>()
        val body = slot<RequestBody>()
        every { s3.putObject(capture(request), capture(body)) } returns PutObjectResponse.builder().build()
        storage.publish(SitemapStorage.INDEX_KEY, "<sitemapindex/>".toByteArray())
        assertThat(request.captured.bucket()).isEqualTo("isolated-test-bucket")
        assertThat(request.captured.key()).isEqualTo("sitemap/index.xml")
        assertThat(request.captured.contentType()).isEqualTo("application/xml; charset=utf-8")
        assertThat(request.captured.cacheControl()).isEqualTo("no-store")
        assertThat(body.captured.contentStreamProvider().newStream().use { it.readBytes() })
            .isEqualTo("<sitemapindex/>".toByteArray())
    }

    @Test
    fun `uploads the temporary file contents without converting them to a byte array`() {
        val file = Files.writeString(tempDir.resolve("posts.xml"), "<urlset/>")
        every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } answers {
            val body = secondArg<RequestBody>()
            assertThat(body.contentStreamProvider().newStream().use { it.readBytes().decodeToString() }).isEqualTo("<urlset/>")
            PutObjectResponse.builder().build()
        }
        storage.publishFile("sitemap/posts-1.xml", file)
    }
}
