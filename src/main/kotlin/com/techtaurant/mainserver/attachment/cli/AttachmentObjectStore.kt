package com.techtaurant.mainserver.attachment.cli

import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.S3Exception
import java.time.Instant

internal data class AttachmentStoredObject(
    val objectKey: String,
    val lastModified: Instant,
)

internal data class AttachmentStoredObjectPage(
    val objects: List<AttachmentStoredObject>,
    val nextContinuationToken: String?,
)

internal interface AttachmentObjectStore {
    fun exists(objectKey: String): Boolean

    fun listObjects(
        continuationToken: String?,
        maxKeys: Int,
    ): AttachmentStoredObjectPage

    fun deleteObjects(objectKeys: List<String>)
}

internal class S3AttachmentObjectStore(
    private val s3Client: S3Client,
    private val bucketName: String,
) : AttachmentObjectStore {
    override fun exists(objectKey: String): Boolean =
        try {
            s3Client.headObject(
                HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build(),
            )
            true
        } catch (exception: S3Exception) {
            if (exception.statusCode() == 404) false else throw exception
        }

    override fun listObjects(
        continuationToken: String?,
        maxKeys: Int,
    ): AttachmentStoredObjectPage {
        val response =
            s3Client.listObjectsV2(
                ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .continuationToken(continuationToken)
                    .maxKeys(maxKeys)
                    .build(),
            )
        return AttachmentStoredObjectPage(
            objects =
                response.contents().map { storedObject ->
                    AttachmentStoredObject(
                        objectKey = storedObject.key(),
                        lastModified = storedObject.lastModified(),
                    )
                },
            nextContinuationToken = response.nextContinuationToken(),
        )
    }

    override fun deleteObjects(objectKeys: List<String>) {
        if (objectKeys.isEmpty()) return
        require(objectKeys.size <= 1_000) { "S3 삭제 배치는 1,000개를 초과할 수 없습니다." }
        val identifiers = objectKeys.map { objectKey -> ObjectIdentifier.builder().key(objectKey).build() }
        val response =
            s3Client.deleteObjects(
                DeleteObjectsRequest.builder()
                    .bucket(bucketName)
                    .delete(Delete.builder().objects(identifiers).build())
                    .build(),
            )
        check(!response.hasErrors()) {
            "S3 객체 일부를 삭제하지 못했습니다: ${response.errors().joinToString { "${it.key()}(${it.code()})" }}"
        }
    }
}
