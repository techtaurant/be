package com.techtaurant.mainserver.attachment.infrastructure.out

import com.techtaurant.mainserver.attachment.entity.Attachment
import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.attachment.enums.AttachmentStatus
import java.util.UUID

interface AttachmentRepositoryCustom {
    fun save(attachment: Attachment): Attachment

    fun saveAll(attachments: Iterable<Attachment>): List<Attachment>

    fun deleteAll(attachments: Iterable<Attachment>)

    fun deleteAllInBatch()

    fun existsById(id: UUID): Boolean

    fun deleteAllByReferenceIdAndReferenceType(
        referenceId: UUID,
        referenceType: AttachmentReferenceType,
    )

    fun findAllById(ids: Iterable<UUID>): List<Attachment>

    fun findAllByIdForUpdate(ids: Iterable<UUID>): List<Attachment>

    fun findAllByObjectKeyInAndStatus(
        objectKeys: List<String>,
        status: AttachmentStatus,
    ): List<Attachment>

    fun findAllByReferenceIdAndReferenceType(
        referenceId: UUID,
        referenceType: AttachmentReferenceType,
    ): List<Attachment>

    fun findAllByReferenceIdAndReferenceTypeAndIdNotIn(
        referenceId: UUID,
        referenceType: AttachmentReferenceType,
        attachmentIds: List<UUID>,
    ): List<Attachment>

    fun findAllByReferenceIdInAndReferenceType(
        referenceIds: List<UUID>,
        referenceType: AttachmentReferenceType,
    ): List<Attachment>
}
