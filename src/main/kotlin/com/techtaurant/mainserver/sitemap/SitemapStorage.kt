package com.techtaurant.mainserver.sitemap

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.time.Duration
import java.time.Instant

data class PublishedSitemap(val etag: String, val lastModified: Instant)

@Component
@ConditionalOnProperty("app.environment", havingValue = "prod")
class SitemapStorage(
    private val s3: S3Client,
    @Value("\${sitemap.bucket-name}") private val bucketName: String,
) {
    init {
        require(bucketName.isNotBlank()) { "SITEMAP_BUCKET_NAME is required in production" }
    }

    fun current(): PublishedSitemap? =
        try {
            val response =
                s3.headObject(
                    HeadObjectRequest.builder().bucket(bucketName).key(KEY)
                        .overrideConfiguration(requestConfiguration().build()).build(),
                )
            PublishedSitemap(requireNotNull(response.eTag()), requireNotNull(response.lastModified()))
        } catch (exception: S3Exception) {
            if (exception.statusCode() == 404) null else throw exception
        }

    fun publish(
        xml: ByteArray,
        previous: PublishedSitemap?,
    ) {
        val request =
            PutObjectRequest.builder().bucket(bucketName).key(KEY)
                .contentType("application/xml; charset=utf-8")
                .cacheControl("no-store")
                // Reject publication if another writer changed the object since our HEAD request.
                .overrideConfiguration(
                    requestConfiguration()
                        .putHeader(if (previous == null) "If-None-Match" else "If-Match", previous?.etag ?: "*")
                        .build(),
                ).build()
        s3.putObject(request, RequestBody.fromBytes(xml))
    }

    private fun requestConfiguration(): AwsRequestOverrideConfiguration.Builder =
        AwsRequestOverrideConfiguration.builder()
            .apiCallTimeout(API_CALL_TIMEOUT)
            .apiCallAttemptTimeout(API_ATTEMPT_TIMEOUT)

    companion object {
        const val KEY = "seo/sitemap.xml"
        private val API_CALL_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val API_ATTEMPT_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
