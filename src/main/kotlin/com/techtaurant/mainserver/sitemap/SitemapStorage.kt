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

/**
 * 공개 S3 버킷의 사이트맵 파일 조회와 업로드를 담당합니다.
 * 버킷은 공통 S3 설정을 사용하고, 객체 경로는 [KEY]로 고정합니다.
 */
@Component
@ConditionalOnProperty("app.environment", havingValue = "prod")
class SitemapStorage(
    private val s3: S3Client,
    @Value("\${aws.s3.public-bucket-name}") private val bucketName: String,
) {
    init {
        require(bucketName.isNotBlank()) { "AWS_S3_PUBLIC_BUCKET_NAME is required in production" }
    }

    /**
     * 현재 게시된 파일의 ETag와 최종 수정 시각을 HEAD 요청으로 조회합니다.
     *
     * @return 파일이 없으면 null. 접근 거부 등 404 이외의 S3 오류는 호출자에게 전달합니다.
     */
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

    /**
     * 완성된 XML을 공개 버킷의 [KEY]에 업로드합니다.
     *
     * 최초 생성은 파일이 없을 때만, 재생성은 조회한 ETag가 유지될 때만 허용합니다.
     * 조회 이후 다른 실행이 파일을 변경하면 S3의 조건부 쓰기가 실패하며, 강제 덮어쓰기로 재시도하지 않습니다.
     *
     * @param xml 게시할 UTF-8 XML 전체 바이트
     * @param previous 생성 시작 시 조회한 기존 파일 정보. 최초 생성이면 null
     */
    fun publish(
        xml: ByteArray,
        previous: PublishedSitemap?,
    ) {
        val request =
            PutObjectRequest.builder().bucket(bucketName).key(KEY)
                .contentType("application/xml; charset=utf-8")
                .cacheControl("no-store")
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
