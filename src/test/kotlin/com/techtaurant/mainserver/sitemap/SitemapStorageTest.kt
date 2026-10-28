package com.techtaurant.mainserver.sitemap

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import java.time.Instant

class SitemapStorageTest {
    private val s3 = mockk<S3Client>()
    private val storage = SitemapStorage(s3, SitemapProperties(true, "isolated-test-bucket"))

    @Test
    fun `only 404 means no prior publication and access errors fail closed`() {
        every { s3.headObject(any<HeadObjectRequest>()) } throws S3Exception.builder().statusCode(404).build()
        assertThat(storage.current()).isNull()
        every { s3.headObject(any<HeadObjectRequest>()) } throws S3Exception.builder().statusCode(403).build()
        assertThatThrownBy { storage.current() }.isInstanceOf(S3Exception::class.java)
    }

    @Test
    fun `loads durable modification time and ETag`() {
        every { s3.headObject(any<HeadObjectRequest>()) } returns
            HeadObjectResponse.builder()
                .eTag("etag").lastModified(Instant.EPOCH).build()
        assertThat(storage.current()).isEqualTo(PublishedSitemap("etag", Instant.EPOCH))
    }

    @Test
    fun `first upload is conditional and subsequent uploads require the previous ETag`() {
        val request = slot<PutObjectRequest>()
        val body = slot<RequestBody>()
        every { s3.putObject(capture(request), capture(body)) } returns PutObjectResponse.builder().build()
        storage.publish("<urlset/>".toByteArray(), null)
        assertThat(request.captured.overrideConfiguration().get().headers()["If-None-Match"]).containsExactly("*")
        assertThat(request.captured.bucket()).isEqualTo("isolated-test-bucket")
        assertThat(request.captured.key()).isEqualTo("sitemap.xml")
        assertThat(request.captured.contentType()).isEqualTo("application/xml; charset=utf-8")
        assertThat(request.captured.cacheControl()).isEqualTo("no-store")
        assertThat(body.captured.contentStreamProvider().newStream().readBytes()).isEqualTo("<urlset/>".toByteArray())
        storage.publish("<urlset/>".toByteArray(), PublishedSitemap("old-etag", Instant.EPOCH))
        assertThat(request.captured.overrideConfiguration().get().headers()["If-Match"]).containsExactly("old-etag")
    }

    @Test
    fun `conditional conflict is propagated without an unconditional overwrite`() {
        every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } throws S3Exception.builder().statusCode(412).build()
        assertThatThrownBy { storage.publish(byteArrayOf(), PublishedSitemap("stale", Instant.EPOCH)) }
            .isInstanceOf(S3Exception::class.java)
    }
}
