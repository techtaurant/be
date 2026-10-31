package com.techtaurant.mainserver.sitemap.infrastructure.out

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest

/** 공개 S3 버킷에 사이트맵 XML을 업로드합니다. */
// TODO: public/static attachment 도메인 정리 시 공개 파일 업로드 기능을 해당 도메인으로 이관합니다.
// https://github.com/techtaurant/be/issues/230
@Component
class S3SitemapStorage(
    private val s3: S3Client,
    @Value("\${aws.s3.public-bucket-name}")
    private val bucketName: String,
) : SitemapStorage {
    /** 완성된 XML을 지정한 경로에 업로드하며, 일시적인 실패의 재시도는 AWS SDK에 맡깁니다. */
    override fun publishXml(
        key: String,
        xml: ByteArray,
    ) {
        val request =
            PutObjectRequest.builder().bucket(bucketName).key(key)
                .contentType("application/xml; charset=utf-8")
                .cacheControl("no-store")
                .build()
        s3.putObject(request, RequestBody.fromBytes(xml))
    }
}
