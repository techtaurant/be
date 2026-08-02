package com.techtaurant.mainserver.attachment.infrastructure.out

import com.techtaurant.mainserver.attachment.entity.Attachment
import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.attachment.enums.AttachmentStatus
import org.springframework.data.repository.Repository
import java.util.UUID

interface AttachmentRepository : Repository<Attachment, UUID>, AttachmentRepositoryCustom {
    override fun save(attachment: Attachment): Attachment

    override fun saveAll(attachments: Iterable<Attachment>): List<Attachment>

    override fun deleteAll(attachments: Iterable<Attachment>)

    override fun deleteAllInBatch()

    override fun existsById(id: UUID): Boolean

    override fun findAllById(ids: Iterable<UUID>): List<Attachment>

    /**
     * 첨부 확정 트랜잭션 동안 요청된 첨부 행을 ID 순서로 잠급니다.
     *
     * @param ids 잠글 첨부 ID 목록
     * @return 존재하는 첨부 목록
     */
    override fun findAllByIdForUpdate(ids: Iterable<UUID>): List<Attachment>

    override fun findAllByObjectKeyInAndStatus(
        objectKeys: List<String>,
        status: AttachmentStatus,
    ): List<Attachment>

    override fun findAllByReferenceIdAndReferenceType(
        referenceId: UUID,
        referenceType: AttachmentReferenceType,
    ): List<Attachment>

    override fun findAllByReferenceIdAndReferenceTypeAndIdNotIn(
        referenceId: UUID,
        referenceType: AttachmentReferenceType,
        attachmentIds: List<UUID>,
    ): List<Attachment>

    override fun findAllByReferenceIdInAndReferenceType(
        referenceIds: List<UUID>,
        referenceType: AttachmentReferenceType,
    ): List<Attachment>

    override fun deleteAllByReferenceIdAndReferenceType(
        referenceId: UUID,
        referenceType: AttachmentReferenceType,
    )
}
