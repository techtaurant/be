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
