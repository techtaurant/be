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
import java.time.Instant
import java.util.UUID

/**
 * 첨부파일 비즈니스 로직 서비스.
 *
 * Presigned URL 발급, 확정 없이 소유만 기록하는 claim, TMP → CONFIRMED 전환(S3 파일 복사 포함),
 * 첨부파일 삭제와 보관 기간이 지난 미확정 첨부 회수를 담당한다.
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
        val contentType = normalizeAllowedContentType(request.contentType)

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
                    contentType = contentType,
                    fileSize = request.fileSize,
                ),
            )

        val presignedUrl =
            s3StorageService.generatePresignedUploadUrl(
                objectKey = objectKey,
                contentType = contentType,
                fileSize = request.fileSize,
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
     * 확정하지 않은 채 첨부의 소유 대상만 기록합니다.
     * status는 TMP, objectKey는 tmp/ 경로 그대로 두므로 미리보기 URL 발급이 계속 동작합니다.
     *
     * 소유 대상이 기록되어야 게시물 삭제와 orphan 정리가 이 첨부를 찾을 수 있습니다.
     * 두 정리 경로 모두 referenceId로 조회하기 때문에, 기록하지 않으면 확정에 도달하지 못한 첨부가
     * 어떤 삭제 경로에도 잡히지 않고 영구히 남는다.
     *
     * @param referenceId 연관 도메인 PK (게시물 ID 등)
     * @param referenceType 연관 도메인 타입
     * @param attachmentIds 소유를 기록할 Attachment ID 목록
     * @throws ApiException 첨부 없음(NOT_FOUND), 대상 타입 불일치·다른 대상이 소유한 첨부(BAD_REQUEST)
     */
    @Transactional
    fun claimTmpAttachments(
        referenceId: UUID,
        referenceType: AttachmentReferenceType,
        attachmentIds: List<UUID>,
    ) {
        if (attachmentIds.isEmpty()) return

        val requestedAttachments = findAttachmentsOwnableBy(referenceId, referenceType, attachmentIds)
        val unclaimedTmpAttachmentIds =
            requestedAttachments
                .filter { it.status == AttachmentStatus.TMP && it.referenceId != referenceId }
                .mapNotNull { it.id }

        if (unclaimedTmpAttachmentIds.isEmpty()) return

        attachmentRepository.updateReferenceIdByIds(referenceId, unclaimedTmpAttachmentIds)
    }

    /**
     * 보관 기간이 지난 미확정 첨부를 S3와 DB에서 삭제합니다.
     * 어느 대상에도 확정되지 못한 첨부는 정리 경로가 referenceId로 찾을 수 없으므로 이 배치가 회수합니다.
     * S3 객체까지 지우는 이유는 tmp/ lifecycle 정책이 설정되어 있지 않을 수 있기 때문이며,
     * 이미 만료된 객체에 대한 삭제는 S3에서 무해하게 무시됩니다.
     *
     * 다른 삭제 경로와 달리 객체를 먼저 지우고 성공한 키의 행만 회수합니다. 지우지 못한 객체를 가리키는 행이
     * 남아야 그 객체를 다시 찾을 수단이 생기고, 실패한 키 하나가 같은 배치의 나머지 회수까지 되돌리지 않습니다.
     * 남은 행은 소유가 기록되지 않은 TMP 상태 그대로라 다음 실행이 같은 조회로 다시 집어 재시도합니다.
     * 이 트랜잭션에는 첨부 정리 뒤에 실패할 단계가 없어 객체를 먼저 지워도 살아 있는 대상이 첨부를 잃지 않습니다.
     *
     * @param expirationThreshold 이 시각 이전에 생성된 첨부가 삭제 대상
     * @param limit 한 번에 삭제할 최대 건수
     * @return 객체까지 지우고 회수한 첨부 수
     */
    @Transactional
    fun deleteExpiredTmpAttachments(
        expirationThreshold: Instant,
        limit: Int,
    ): Int {
        val expiredAttachments =
            attachmentRepository.findAllUnclaimedByStatusAndCreatedAtBefore(AttachmentStatus.TMP, expirationThreshold, limit)

        if (expiredAttachments.isEmpty()) return 0

        val undeletedObjectKeys = s3StorageService.deleteObjects(expiredAttachments.map { it.objectKey }).toSet()
        val reclaimedAttachments = expiredAttachments.filterNot { it.objectKey in undeletedObjectKeys }

        if (undeletedObjectKeys.isNotEmpty()) {
            log.warn("Keeping expired attachments whose S3 objects remain: {}", undeletedObjectKeys)
        }

        attachmentRepository.deleteAll(reclaimedAttachments)

        return reclaimedAttachments.size
    }

    /**
     * 여러 소유 대상에 연결된 첨부를 S3와 DB에서 한 번에 삭제합니다.
     * 만료된 임시저장을 정리할 때 대상마다 조회를 반복하지 않도록 배치 경로가 사용합니다.
     *
     * @param referenceIds 연관 도메인 PK 목록
     * @param referenceType 연관 도메인 타입
     * @return 삭제한 첨부 수
     */
    @Transactional
    fun deleteAttachmentsByReferenceIds(
        referenceIds: List<UUID>,
        referenceType: AttachmentReferenceType,
    ): Int {
        if (referenceIds.isEmpty()) return 0

        val attachments = attachmentRepository.findAllByReferenceIdInAndReferenceType(referenceIds, referenceType)
        if (attachments.isEmpty()) return 0

        deleteAttachmentsWithObjects(attachments)

        return attachments.size
    }

    /**
     * 첨부 행을 지우고 S3 객체 삭제를 커밋 직전으로 예약합니다.
     * 첨부 정리 뒤에 실패할 단계가 남아 있는 경로가 모두 같은 순서를 쓰도록 이 함수를 거친다.
     */
    private fun deleteAttachmentsWithObjects(attachments: List<Attachment>) {
        attachmentRepository.deleteAll(attachments)
        deleteObjectsBeforeCommit(attachments.map { it.objectKey })
    }

    /**
     * 요청한 첨부를 잠금 조회한 뒤 이 대상이 사용할 수 있는지 검증하고 요청 순서대로 반환합니다.
     * 확정과 소유 기록이 같은 조건을 공유해야 임시저장 단계에서 통과한 첨부가 발행 단계에서 거부되는 일이 없습니다.
     *
     * 발급 시 지정한 도메인 타입과 다른 타입을 넘기면 호출부가 그 ID를 썸네일 FK로 저장하는데,
     * 읽기 경로는 해당 타입의 CONFIRMED 첨부만 조회하므로 썸네일이 조용히 대체되고 업로드된 파일은 tmp/에 남는다.
     * 다른 대상이 이미 소유한 첨부도 같은 이유로 거부한다. FK 제약은 통과하지만 읽기 경로가
     * 자기 대상의 첨부만 조회하므로 썸네일이 조용히 기본 이미지로 대체된다.
     */
    private fun findAttachmentsOwnableBy(
        referenceId: UUID,
        referenceType: AttachmentReferenceType,
        attachmentIds: List<UUID>,
    ): List<Attachment> {
        val distinctAttachmentIds = attachmentIds.distinct()
        val attachmentsById = attachmentRepository.findAllByIdForUpdate(distinctAttachmentIds).associateBy { it.id!! }

        if (attachmentsById.size != distinctAttachmentIds.size) {
            throw ApiException(DefaultStatus.NOT_FOUND, "첨부파일을 찾을 수 없습니다")
        }

        val requestedAttachments = distinctAttachmentIds.mapNotNull(attachmentsById::get)

        if (requestedAttachments.any { it.referenceType != referenceType }) {
            throw ApiException(DefaultStatus.BAD_REQUEST, "요청한 대상 타입과 다른 첨부파일은 사용할 수 없습니다")
        }

        if (requestedAttachments.any { it.referenceId != null && it.referenceId != referenceId }) {
            throw ApiException(DefaultStatus.BAD_REQUEST, "다른 대상에 연결된 첨부파일은 사용할 수 없습니다")
        }

        return requestedAttachments
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
     * @throws ApiException 첨부 없음(NOT_FOUND), 대상 타입 불일치·다른 대상이 소유한 첨부·업로드 미완료(BAD_REQUEST)
     */
    @Transactional
    fun confirmAttachmentsByIds(
        referenceId: UUID,
        referenceType: AttachmentReferenceType,
        attachmentIds: List<UUID>,
    ) {
        if (attachmentIds.isEmpty()) return

        val requestedAttachments = findAttachmentsOwnableBy(referenceId, referenceType, attachmentIds)
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
        deleteObjectsBeforeCommit(attachments.map { it.objectKey })
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

        deleteAttachmentsWithObjects(orphaned)
    }

    /**
     * S3 객체 삭제를 트랜잭션 커밋 직전으로 예약합니다.
     *
     * 키 하나라도 지우지 못하면 예외를 던져 트랜잭션을 롤백시키므로, 객체가 남아 있는 한
     * 그 객체를 가리키는 첨부 행도 함께 남는다. 덕분에 다음 요청이 같은 키로 재시도할 수 있고,
     * 커밋 이후로 미뤘을 때처럼 아무도 참조하지 않는 객체가 버킷에 쌓이지 않는다.
     * 이미 지워진 키를 다시 지우는 요청은 [S3StorageService.deleteObjects]가 성공으로 취급하므로
     * 재시도가 안전하다.
     *
     * 성공한 키의 행만 남기고 지우는 부분 회수는 여기서 하지 않는다. 커밋 직전이라 이미 지운 행을 되살릴
     * 수단이 없고, 이 순서를 쓰는 경로는 첨부를 지운 뒤에도 실패할 단계가 남아 있어 전부 되돌리는 편이 안전하다.
     *
     * 다만 키가 [S3StorageService]의 요청당 상한을 넘어 여러 요청으로 나뉘면 일부 요청만 반영된 채
     * 롤백될 수 있고, 그때는 살아남은 첨부 행이 존재하지 않는 객체를 가리킨다.
     *
     * @param objectKeys 삭제할 S3 오브젝트 키 목록
     */
    private fun deleteObjectsBeforeCommit(objectKeys: List<String>) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun beforeCommit(readOnly: Boolean) {
                    log.info("Deleting S3 objects before commit: {}", objectKeys)
                    val undeletedObjectKeys = s3StorageService.deleteObjects(objectKeys)
                    check(undeletedObjectKeys.isEmpty()) {
                        "S3 오브젝트 삭제에 실패했습니다: " + undeletedObjectKeys.joinToString()
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

    /**
     * 허용 목록에 있는 MIME 타입인지 검증하고, 저장·서명에 함께 쓸 정규화된 값을 돌려준다.
     * 검증만 정규화하면 대소문자·공백이 섞인 원본이 DB와 S3 객체 Content-Type에 그대로 남는다.
     */
    private fun normalizeAllowedContentType(contentType: String): String {
        val normalized = contentType.trim().lowercase()

        if (normalized !in ALLOWED_CONTENT_TYPES) {
            throw ApiException(
                DefaultStatus.BAD_REQUEST,
                "지원하지 않는 파일 형식입니다. 허용 형식: ${ALLOWED_CONTENT_TYPES.joinToString()}",
            )
        }

        return normalized
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

    companion object {
        /**
         * 업로드를 허용하는 MIME 타입.
         *
         * 글 본문 렌더러가 sanitize 단계에서 img·picture·source만 남기므로, 업로드해도 화면에
         * 표시되지 않는 비디오·오디오는 제외한다. image/svg+xml은 파일 자체가 스크립트를 담을 수 있어
         * 링크를 직접 열면 실행될 수 있으므로 이미지 중에서도 제외한다.
         *
         * 선언된 값만 검증하므로 확장자를 위장한 파일은 걸러내지 못한다.
         */
        private val ALLOWED_CONTENT_TYPES =
            setOf(
                "image/jpeg",
                "image/png",
                "image/gif",
                "image/webp",
                "image/avif",
            )
    }
}
