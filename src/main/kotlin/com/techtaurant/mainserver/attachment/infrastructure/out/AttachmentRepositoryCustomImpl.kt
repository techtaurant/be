package com.techtaurant.mainserver.attachment.infrastructure.out

import com.techtaurant.mainserver.attachment.entity.Attachment
import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.attachment.enums.AttachmentStatus
import com.techtaurant.mainserver.jooq.tables.Attachments.Companion.ATTACHMENTS
import com.techtaurant.mainserver.jooq.tables.records.AttachmentsRecord
import org.jooq.Condition
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AttachmentRepositoryCustomImpl(
    private val dsl: DSLContext,
) : AttachmentRepositoryCustom {
    override fun findAllById(ids: Iterable<UUID>): List<Attachment> {
        val attachmentIds = ids.toList()
        return if (attachmentIds.isEmpty()) emptyList() else fetchAttachments(ATTACHMENTS.ID.`in`(attachmentIds))
    }

    override fun findAllByObjectKeyInAndStatus(
        objectKeys: List<String>,
        status: AttachmentStatus,
    ): List<Attachment> =
        if (objectKeys.isEmpty()) {
            emptyList()
        } else {
            fetchAttachments(
                ATTACHMENTS.OBJECT_KEY.`in`(objectKeys).and(ATTACHMENTS.STATUS.cast(String::class.java).eq(status.name)),
            )
        }

    override fun findAllByReferenceIdAndReferenceType(
        referenceId: UUID,
        referenceType: AttachmentReferenceType,
    ): List<Attachment> = fetchAttachments(referenceCondition(referenceId, referenceType))

    override fun findAllByReferenceIdAndReferenceTypeAndIdNotIn(
        referenceId: UUID,
        referenceType: AttachmentReferenceType,
        attachmentIds: List<UUID>,
    ): List<Attachment> =
        fetchAttachments(
            referenceCondition(
                referenceId,
                referenceType,
            ).and(if (attachmentIds.isEmpty()) org.jooq.impl.DSL.trueCondition() else ATTACHMENTS.ID.notIn(attachmentIds)),
        )

    override fun findAllByReferenceIdInAndReferenceType(
        referenceIds: List<UUID>,
        referenceType: AttachmentReferenceType,
    ): List<Attachment> =
        if (referenceIds.isEmpty()) {
            emptyList()
        } else {
            fetchAttachments(
                ATTACHMENTS.REFERENCE_ID.`in`(referenceIds).and(ATTACHMENTS.REFERENCE_TYPE.cast(String::class.java).eq(referenceType.name)),
            )
        }

    private fun referenceCondition(
        referenceId: UUID,
        referenceType: AttachmentReferenceType,
    ): Condition = ATTACHMENTS.REFERENCE_ID.eq(referenceId).and(ATTACHMENTS.REFERENCE_TYPE.cast(String::class.java).eq(referenceType.name))

    private fun fetchAttachments(condition: Condition): List<Attachment> =
        dsl.selectFrom(ATTACHMENTS).where(condition).fetch().map { record -> record.toAttachment() }

    private fun AttachmentsRecord.toAttachment(): Attachment =
        Attachment(
            referenceId = referenceId,
            referenceType = AttachmentReferenceType.valueOf(requireNotNull(referenceType).toString()),
            objectKey = requireNotNull(objectKey),
            status = AttachmentStatus.valueOf(requireNotNull(status).toString()),
            originalFileName = requireNotNull(originalFileName),
            contentType = requireNotNull(contentType),
            fileSize = requireNotNull(fileSize),
        ).apply {
            id = requireNotNull(this@toAttachment.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }
}
