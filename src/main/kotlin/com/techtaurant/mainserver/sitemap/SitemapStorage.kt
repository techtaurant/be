package com.techtaurant.mainserver.sitemap

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.nio.file.Path

/** 공개 S3 버킷의 사이트맵 파일 업로드를 담당합니다. 버킷은 공통 S3 설정을 사용합니다. */
@Component
class SitemapStorage(
    private val s3: S3Client,
    @Value("\${aws.s3.public-bucket-name}")
    private val bucketName: String,
) {
    /** 완성된 XML을 지정한 경로에 업로드하며, 기존 파일이 있으면 덮어씁니다. */
    fun publish(
        key: String,
        xml: ByteArray,
    ) = publishBody(key, RequestBody.fromBytes(xml))

    /** 임시 XML 파일을 메모리에 다시 적재하지 않고 업로드합니다. 업로드 완료까지 파일을 유지해야 합니다. */
    fun publishFile(
        key: String,
        file: Path,
    ) = publishBody(key, RequestBody.fromFile(file))

    private fun publishBody(
        key: String,
        body: RequestBody,
    ) {
        val request =
            PutObjectRequest.builder().bucket(bucketName).key(key)
                .contentType("application/xml; charset=utf-8")
                .cacheControl("no-store")
                .build()
        s3.putObject(request, body)
    }

    companion object {
        const val HOME_KEY = "sitemap/home.xml"
        const val INDEX_KEY = "sitemap/index.xml"
    }
}
