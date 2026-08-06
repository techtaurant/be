package com.techtaurant.mainserver.attachment.application

import com.techtaurant.mainserver.attachment.dto.AttachmentPreviewUrlResponse
import com.techtaurant.mainserver.attachment.dto.AttachmentPreviewUrlsRequest
import com.techtaurant.mainserver.attachment.dto.PresignedUrlRequest
import com.techtaurant.mainserver.attachment.dto.PresignedUrlResponse
import com.techtaurant.mainserver.attachment.entity.Attachment
import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.attachment.enums.AttachmentStatus
import com.techtaurant.mainserver.attachment.infrastructure.out.AttachmentRepository
import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.common.status.DefaultStatus
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/**
 * 첨부파일 비즈니스 로직 서비스.
 *
 * Presigned URL 발급, TMP → CONFIRMED 전환(S3 파일 복사 포함),
 * 첨부파일 삭제를 담당한다.
 */
@Service
class AttachmentService(
    private val attachmentRepository: AttachmentRepository,
    private val s3StorageService: S3StorageService,
    @param:Value("\${aws.s3.presigned-url-expire-minutes}")
    private val presignedUrlExpireMinutes: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * S3 PUT Presigned URL을 발급하고 TMP 상태의 Attachment 레코드를 생성합니다.
     *
     * @param request Presigned URL 발급 요청 (파일명, MIME 타입, 파일 크기, 도메인 타입)
     * @return Presigned URL, objectKey, attachmentId 응답
     */
    @Transactional
    fun issuePresignedUploadUrl(request: PresignedUrlRequest): PresignedUrlResponse {
        val uniqueId = UUID.randomUUID()
        val objectKey = "tmp/$uniqueId/${request.fileName}"

        val attachment =
            attachmentRepository.save(
                Attachment(
                    referenceId = null,
                    referenceType = request.referenceType,
                    objectKey = objectKey,
                    status = AttachmentStatus.TMP,
                    originalFileName = request.fileName,
                    contentType = request.contentType,
                    fileSize = request.fileSize,
                ),
            )

        val presignedUrl =
            s3StorageService.generatePresignedUploadUrl(
                objectKey = objectKey,
                contentType = request.contentType,
                expireMinutes = presignedUrlExpireMinutes,
            )

        return PresignedUrlResponse.from(attachment, presignedUrl)
    }

    /**
     * TMP 상태 첨부파일의 미리보기용 GET Presigned URL을 발급합니다.
     * 게시물 발행 전 미리보기에서 임시 이미지를 표시할 때 사용합니다.
     *
     * @param attachmentId 미리보기할 TMP Attachment ID
     * @return attachmentId, objectKey, presignedUrl 응답
     */
    @Transactional(readOnly = true)
    fun issueTmpPreviewUrl(attachmentId: UUID): AttachmentPreviewUrlResponse {
        return issueTmpPreviewUrls(AttachmentPreviewUrlsRequest(listOf(attachmentId))).first()
    }

    /**
     * TMP 상태 첨부파일 여러 건의 미리보기용 GET Presigned URL을 발급합니다.
     * 요청 순서를 유지하며, 모든 첨부파일이 TMP 상태여야 합니다.
     *
     * @param request 미리보기할 TMP Attachment ID 목록
     * @return attachmentId, objectKey, presignedUrl 응답 목록
     */
    @Transactional(readOnly = true)
    fun issueTmpPreviewUrls(request: AttachmentPreviewUrlsRequest): List<AttachmentPreviewUrlResponse> {
        val attachmentIds = request.attachmentIds.distinct()
        val attachmentsById = attachmentRepository.findAllById(attachmentIds).associateBy { it.id!! }

        return attachmentIds.map { attachmentId ->
            val attachment =
                attachmentsById[attachmentId]
                    ?: throw ApiException(DefaultStatus.NOT_FOUND, "임시 첨부파일을 찾을 수 없습니다")

            validateTmpPreviewAttachment(attachment)

            val presignedUrl =
                s3StorageService.generatePresignedDownloadUrl(
                    objectKey = attachment.objectKey,
                    expireMinutes = presignedUrlExpireMinutes,
                )

            AttachmentPreviewUrlResponse.from(attachment, presignedUrl)
        }
    }

    /**
     * attachmentId에 해당하는 TMP Attachment를 CONFIRMED 상태로 전환합니다.
     * S3 파일을 tmp/ 경로에서 referenceType에 맞는 확정 경로로 복사하며, tmp/ 원본은 지우지 않습니다.
     * 원본을 커밋 전에 지우면 이후 단계나 커밋이 실패했을 때 DB는 tmp/ 키로 롤백되는데 그 객체가 없어
     * 재시도가 불가능해지므로, 원본 정리는 tmp/ 경로의 S3 lifecycle 만료 정책에 맡긴다.
     *
     * @param referenceId 연관 도메인 PK (게시물 ID 등)
     * @param referenceType 연관 도메인 타입
     * @param attachmentIds 확정할 Attachment ID 목록
     * @throws ApiException 첨부 없음(NOT_FOUND), 대상 타입 불일치·다른 대상에 확정된 첨부·업로드 미완료(BAD_REQUEST)
     */
    @Transactional
    fun confirmAttachmentsByIds(
        referenceId: UUID,
        referenceType: AttachmentReferenceType,
        attachmentIds: List<UUID>,
    ) {
        if (attachmentIds.isEmpty()) return

        val distinctAttachmentIds = attachmentIds.distinct()
        val attachmentsById = attachmentRepository.findAllByIdForUpdate(distinctAttachmentIds).associateBy { it.id!! }

        if (attachmentsById.size != distinctAttachmentIds.size) {
            throw ApiException(DefaultStatus.NOT_FOUND, "첨부파일을 찾을 수 없습니다")
        }

        val requestedAttachments = distinctAttachmentIds.mapNotNull(attachmentsById::get)

        // 발급 시 지정한 도메인 타입과 다른 타입으로 확정을 요청하면 아래 TMP 필터에서 조용히 제외된다.
        // 그대로 성공을 반환하면 호출부가 미확정 ID를 썸네일 FK로 저장하는데, 읽기 경로는 해당 타입의
        // CONFIRMED 첨부만 조회하므로 썸네일이 조용히 대체되고 업로드된 파일은 tmp/에 남는다.
        val attachmentsWithMismatchedReferenceType = requestedAttachments.filter { it.referenceType != referenceType }

        if (attachmentsWithMismatchedReferenceType.isNotEmpty()) {
            throw ApiException(DefaultStatus.BAD_REQUEST, "요청한 대상 타입과 다른 첨부파일은 사용할 수 없습니다")
        }

        // 이미 확정된 첨부는 아래 TMP 필터에서 제외되어 이 리소스로 재바인딩되지 않는다.
        // 다른 리소스의 확정 첨부를 그대로 받으면 FK 제약은 통과하지만, 읽기 경로가 해당 리소스의
        // 첨부만 조회하므로 썸네일이 조용히 기본 이미지로 대체된다. 따라서 여기서 거부한다.
        val attachmentsOwnedByOtherReference =
            requestedAttachments.filter { attachment ->
                attachment.status == AttachmentStatus.CONFIRMED &&
                    attachment.referenceId != null &&
                    attachment.referenceId != referenceId
            }

        if (attachmentsOwnedByOtherReference.isNotEmpty()) {
            throw ApiException(DefaultStatus.BAD_REQUEST, "다른 대상에 연결된 첨부파일은 사용할 수 없습니다")
        }

        val tmpAttachments = requestedAttachments.filter { it.status == AttachmentStatus.TMP }

        if (tmpAttachments.isEmpty()) return

        // 업로드가 끝나지 않은 첨부를 건너뛰고 성공을 반환하면 호출부가 그 ID를 썸네일 FK로 저장한다.
        // FK 제약은 통과하지만 읽기 경로가 CONFIRMED 첨부만 조회하므로 썸네일이 조용히 대체되고,
        // 수정 경로에서는 직전까지 정상이던 첨부가 orphan으로 삭제된다. 그래서 요청 자체를 거부한다.
        val attachmentsWithoutUploadedObject = tmpAttachments.filterNot { s3StorageService.exists(it.objectKey) }

        if (attachmentsWithoutUploadedObject.isNotEmpty()) {
            log.warn("S3 objects not found for confirmation: {}", attachmentsWithoutUploadedObject.map { it.objectKey })
            throw ApiException(DefaultStatus.BAD_REQUEST, "업로드가 완료되지 않은 첨부파일은 사용할 수 없습니다")
        }

        tmpAttachments.forEach { attachment ->
            val tmpObjectKey = attachment.objectKey
            val uniqueId = UUID.randomUUID()
            val fileName = tmpObjectKey.substringAfterLast("/")
            val newObjectKey = buildConfirmedObjectKey(referenceType, referenceId, uniqueId, fileName)

            s3StorageService.copyObject(
                sourceKey = tmpObjectKey,
                destinationKey = newObjectKey,
            )

            attachment.objectKey = newObjectKey
            attachment.status = AttachmentStatus.CONFIRMED
            attachment.referenceId = referenceId
            attachment.referenceType = referenceType
        }

        attachmentRepository.saveAll(tmpAttachments)
    }

    /**
     * referenceId에 연결된 모든 첨부파일을 S3와 DB에서 삭제합니다.
     *
     * @param referenceId 연관 도메인 PK
     * @param referenceType 연관 도메인 타입
     */
    @Transactional
    fun deleteAttachmentsByReference(
        referenceId: UUID,
        referenceType: AttachmentReferenceType,
    ) {
        val attachments = attachmentRepository.findAllByReferenceIdAndReferenceType(referenceId, referenceType)
        if (attachments.isEmpty()) return

        attachmentRepository.deleteAllByReferenceIdAndReferenceType(referenceId, referenceType)
        deleteObjectsAfterCommit(attachments.map { it.objectKey })
    }

    /**
     * keepAttachmentIds에 포함되지 않는 첨부파일을 S3와 DB에서 삭제합니다.
     * 게시물 수정 시 content에서 제거된 이미지 정리에 사용됩니다.
     *
     * @param referenceId 연관 도메인 PK
     * @param referenceType 연관 도메인 타입
     * @param keepAttachmentIds 유지할 attachmentId 목록
     */
    @Transactional
    fun deleteOrphanedAttachmentsByIds(
        referenceId: UUID,
        referenceType: AttachmentReferenceType,
        keepAttachmentIds: List<UUID>,
    ) {
        val orphaned =
            if (keepAttachmentIds.isEmpty()) {
                attachmentRepository.findAllByReferenceIdAndReferenceType(referenceId, referenceType)
            } else {
                attachmentRepository.findAllByReferenceIdAndReferenceTypeAndIdNotIn(
                    referenceId = referenceId,
                    referenceType = referenceType,
                    attachmentIds = keepAttachmentIds.distinct(),
                )
            }
        if (orphaned.isEmpty()) return

        attachmentRepository.deleteAll(orphaned)
        deleteObjectsAfterCommit(orphaned.map { it.objectKey })
    }

    /**
     * S3 객체 삭제를 트랜잭션 커밋 이후로 미룹니다.
     * 커밋 전에 지우면 롤백됐을 때 DB에는 첨부 행이 남고 S3 객체만 사라져,
     * 정상으로 보이는 presigned URL이 존재하지 않는 객체를 가리키게 된다.
     * 커밋 뒤로 미루면 실패해도 참조되지 않는 객체만 남으므로 조회 결과가 깨지지 않는다.
     *
     * @param objectKeys 삭제할 S3 오브젝트 키 목록
     */
    private fun deleteObjectsAfterCommit(objectKeys: List<String>) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    log.info("Deleting S3 objects after commit: {}", objectKeys)
                    try {
                        s3StorageService.deleteObjects(objectKeys)
                    } catch (e: Exception) {
                        // afterCommit 예외는 호출자에게 전파되어 이미 커밋된 요청이 실패로 보이고,
                        // 클라이언트가 반영이 끝난 상태에 재시도하게 된다. 정리 실패의 결과는
                        // 참조되지 않는 객체가 남는 것뿐이므로 여기서 가두고 로그로만 남긴다.
                        log.error("Failed to delete S3 objects after commit: {}", objectKeys, e)
                    }
                }
            },
        )
    }

    /**
     * 특정 referenceId에 연결된 CONFIRMED 첨부파일 목록을 조회합니다.
     *
     * @param referenceId 연관 도메인 PK
     * @param referenceType 연관 도메인 타입
     * @return 첨부파일 목록
     */
    @Transactional(readOnly = true)
    fun getConfirmedAttachments(
        referenceId: UUID,
        referenceType: AttachmentReferenceType,
    ): List<Attachment> =
        attachmentRepository.findAllByReferenceIdAndReferenceType(referenceId, referenceType)
            .filter { it.status == AttachmentStatus.CONFIRMED }

    /**
     * referenceId에 연결된 CONFIRMED 첨부파일의 objectKey → presigned GET URL 맵을 반환합니다.
     * content 내 object key를 접근 가능한 URL로 교체할 때 사용합니다.
     *
     * @param referenceId 연관 도메인 PK
     * @param referenceType 연관 도메인 타입
     * @return objectKey → presigned GET URL 맵
     */
    @Transactional(readOnly = true)
    fun generatePresignedDownloadUrlMap(
        referenceId: UUID,
        referenceType: AttachmentReferenceType,
    ): Map<String, String> {
        val attachments = getConfirmedAttachments(referenceId, referenceType)

        return attachments.associate { attachment ->
            attachment.objectKey to
                s3StorageService.generatePresignedDownloadUrl(
                    objectKey = attachment.objectKey,
                    expireMinutes = presignedUrlExpireMinutes,
                )
        }
    }

    /**
     * referenceId에 연결된 CONFIRMED 첨부파일의 attachmentId → presigned GET URL 맵을 반환합니다.
     * content 내 attachmentId를 접근 가능한 URL로 교체할 때 사용합니다.
     *
     * @param referenceId 연관 도메인 PK
     * @param referenceType 연관 도메인 타입
     * @return attachmentId → presigned GET URL 맵
     */
    @Transactional(readOnly = true)
    fun generatePresignedDownloadUrlMapByReference(
        referenceId: UUID,
        referenceType: AttachmentReferenceType,
    ): Map<UUID, String> {
        return getConfirmedAttachments(referenceId, referenceType)
            .associate { attachment ->
                attachment.id!! to
                    s3StorageService.generatePresignedDownloadUrl(
                        objectKey = attachment.objectKey,
                        expireMinutes = presignedUrlExpireMinutes,
                    )
            }
    }

    /**
     * 전달된 첨부파일 목록의 attachmentId → presigned GET URL 맵을 반환합니다.
     * 이미 조회된 첨부파일 목록을 재사용할 때 사용합니다.
     *
     * @param attachments presigned URL 발급 대상 첨부파일 목록
     * @return attachmentId → presigned GET URL 맵
     */
    @Transactional(readOnly = true)
    fun generatePresignedDownloadUrlMapByAttachments(attachments: List<Attachment>): Map<UUID, String> {
        return attachments
            .filter { it.status == AttachmentStatus.CONFIRMED }
            .associate { attachment ->
                attachment.id!! to
                    s3StorageService.generatePresignedDownloadUrl(
                        objectKey = attachment.objectKey,
                        expireMinutes = presignedUrlExpireMinutes,
                    )
            }
    }

    private fun validateTmpPreviewAttachment(attachment: Attachment) {
        if (attachment.status != AttachmentStatus.TMP || !attachment.objectKey.startsWith("tmp/")) {
            throw ApiException(DefaultStatus.BAD_REQUEST, "TMP 상태의 첨부파일만 미리보기 URL을 발급할 수 있습니다")
        }

        if (!s3StorageService.exists(attachment.objectKey)) {
            throw ApiException(DefaultStatus.NOT_FOUND, "S3에 임시 첨부파일이 존재하지 않습니다")
        }
    }

    private fun buildConfirmedObjectKey(
        referenceType: AttachmentReferenceType,
        referenceId: UUID,
        uniqueId: UUID,
        fileName: String,
    ): String {
        val prefix =
            when (referenceType) {
                AttachmentReferenceType.POST -> "posts"
                AttachmentReferenceType.USER -> "users"
            }

        return "$prefix/$referenceId/$uniqueId/$fileName"
    }

    /**
     * referenceId 목록에 연결된 CONFIRMED 첨부파일을 배치 조회합니다.
     * 게시물 목록 조회 시 썸네일 계산에 사용됩니다.
     *
     * @param referenceIds 연관 도메인 PK 목록
     * @param referenceType 연관 도메인 타입
     * @return referenceId → 첨부파일 목록 맵
     */
    @Transactional(readOnly = true)
    fun getConfirmedAttachmentsByReferenceIds(
        referenceIds: List<UUID>,
        referenceType: AttachmentReferenceType,
    ): Map<UUID, List<Attachment>> =
        attachmentRepository
            .findAllByReferenceIdInAndReferenceType(referenceIds, referenceType)
            .filter { it.status == AttachmentStatus.CONFIRMED }
            .groupBy { it.referenceId!! }
}
