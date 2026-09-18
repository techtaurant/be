package com.techtaurant.mainserver.attachment.application

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.time.Duration

/**
 * S3 파일 I/O 작업 서비스.
 *
 * Presigned URL 생성, 파일 복사, 배치 삭제를 담당한다.
 * 비즈니스 로직 없이 AWS S3 API 호출만 수행한다.
 */
@Service
class S3StorageService(
    private val s3Client: S3Client,
    private val s3Presigner: S3Presigner,
    @Value("\${aws.s3.bucket-name}")
    private val bucketName: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * S3 PUT Presigned URL을 생성합니다.
     *
     * fileSize를 지정하면 Content-Length가 서명 헤더(X-Amz-SignedHeaders)에 포함되므로,
     * S3가 수신한 실제 Content-Length로 서명을 재계산해 값이 다르면 업로드를 거부한다.
     * 덕분에 크기 제한이 확정 단계의 사후 검증이 아니라 업로드 시점에 강제된다.
     *
     * @param objectKey 업로드 대상 S3 오브젝트 키
     * @param contentType 파일 MIME 타입
     * @param fileSize 업로드를 허용할 정확한 바이트 수
     * @param expireMinutes URL 만료 시간 (분)
     * @return Presigned URL 문자열
     */
    fun generatePresignedUploadUrl(
        objectKey: String,
        contentType: String,
        fileSize: Long,
        expireMinutes: Long,
    ): String {
        val putObjectRequest =
            PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .contentType(contentType)
                .contentLength(fileSize)
                .build()

        val presignRequest =
            PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(expireMinutes))
                .putObjectRequest(putObjectRequest)
                .build()

        return s3Presigner.presignPutObject(presignRequest).url().toString()
    }

    /**
     * S3 GET Presigned URL을 생성합니다.
     *
     * @param objectKey 다운로드 대상 S3 오브젝트 키
     * @param expireMinutes URL 만료 시간 (분)
     * @return Presigned URL 문자열
     */
    fun generatePresignedDownloadUrl(
        objectKey: String,
        expireMinutes: Long,
    ): String {
        val getObjectRequest =
            software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .build()

        val presignRequest =
            GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(expireMinutes))
                .getObjectRequest(getObjectRequest)
                .build()

        return s3Presigner.presignGetObject(presignRequest).url().toString()
    }

    /**
     * S3 오브젝트 존재 여부를 확인합니다.
     *
     * @param objectKey 확인 대상 S3 오브젝트 키
     * @return 존재하면 true, 아니면 false
     */
    fun exists(objectKey: String): Boolean {
        return try {
            val request =
                HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build()
            s3Client.headObject(request)
            true
        } catch (e: NoSuchKeyException) {
            false
        } catch (e: Exception) {
            // NoSuchKeyException 외에도 404 에러가 발생할 수 있음
            false
        }
    }

    /**
     * S3 오브젝트를 다른 키로 복사합니다.
     *
     * @param sourceKey 복사 원본 오브젝트 키
     * @param destinationKey 복사 대상 오브젝트 키
     */
    fun copyObject(
        sourceKey: String,
        destinationKey: String,
    ) {
        val request =
            CopyObjectRequest.builder()
                .sourceBucket(bucketName)
                .sourceKey(sourceKey)
                .destinationBucket(bucketName)
                .destinationKey(destinationKey)
                .build()

        s3Client.copyObject(request)
    }

    /**
     * S3 오브젝트 여러 개를 배치로 삭제하고 지우지 못한 키를 돌려줍니다.
     *
     * DeleteObjects API는 요청당 키 [MAX_KEYS_PER_DELETE_REQUEST]개가 상한이고 넘기면 MalformedXML로 거절하므로
     * 요청을 그 단위로 나눠 보냅니다. 호출부마다 상한을 계산하지 않도록 API 제약을 아는 이 계층이 맡습니다.
     *
     * 이미 지워진 키는 삭제 성공으로 취급합니다. 그 밖의 키 단위 실패는 예외로 끊지 않고 남은 키로 알려서,
     * 호출부가 성공한 키의 첨부 행만 지우고 실패한 키는 다음 정리가 다시 시도하도록 남길 수 있게 합니다.
     * 한 청크가 실패해도 나머지 청크는 그대로 시도합니다.
     *
     * @param objectKeys 삭제할 오브젝트 키 목록
     * @return 지우지 못하고 버킷에 남은 오브젝트 키 목록
     */
    fun deleteObjects(objectKeys: List<String>): List<String> = objectKeys.chunked(MAX_KEYS_PER_DELETE_REQUEST).flatMap(::deleteObjectChunk)

    private fun deleteObjectChunk(objectKeys: List<String>): List<String> {
        val identifiers =
            objectKeys.map { key ->
                ObjectIdentifier.builder().key(key).build()
            }

        val request =
            DeleteObjectsRequest.builder()
                .bucket(bucketName)
                .delete(Delete.builder().objects(identifiers).build())
                .build()

        val response = s3Client.deleteObjects(request)

        // DeleteObjects는 일부 키만 실패해도 요청 자체는 성공으로 응답하므로, 응답을 읽지 않으면
        // 남아 있는 객체를 지운 것으로 착각한 채 진행하게 된다.
        val undeleted = response.errors().filterNot { it.code() == ALREADY_DELETED_ERROR_CODE }
        if (undeleted.isNotEmpty()) {
            log.warn("Failed to delete S3 objects: {}", undeleted.joinToString { "${it.key()}(${it.code()})" })
        }

        return undeleted.map { it.key() }
    }

    companion object {
        private const val MAX_KEYS_PER_DELETE_REQUEST = 1000

        /** 지우려는 키가 이미 없을 때 돌아오는 코드. 정리 재시도가 이 때문에 실패하면 안 되므로 성공으로 본다. */
        private const val ALREADY_DELETED_ERROR_CODE = "NoSuchKey"
    }
}
