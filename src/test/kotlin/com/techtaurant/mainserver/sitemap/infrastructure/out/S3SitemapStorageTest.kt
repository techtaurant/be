package com.techtaurant.mainserver.sitemap.infrastructure.out

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse

class S3SitemapStorageTest {
    private val s3 = mockk<S3Client>()
    private val storage = S3SitemapStorage(s3, "isolated-test-bucket")

    @Test
    fun `uploads XML to the configured public bucket`() {
        val request = slot<PutObjectRequest>()
        val body = slot<RequestBody>()
        every { s3.putObject(capture(request), capture(body)) } returns PutObjectResponse.builder().build()
        storage.publishXml("sitemap/index.xml", "<sitemapindex/>".toByteArray())
        assertThat(request.captured.bucket()).isEqualTo("isolated-test-bucket")
        assertThat(request.captured.key()).isEqualTo("sitemap/index.xml")
        assertThat(request.captured.contentType()).isEqualTo("application/xml; charset=utf-8")
        assertThat(request.captured.cacheControl()).isEqualTo("no-store")
        assertThat(body.captured.contentStreamProvider().newStream().use { it.readBytes() })
            .isEqualTo("<sitemapindex/>".toByteArray())
    }
}
