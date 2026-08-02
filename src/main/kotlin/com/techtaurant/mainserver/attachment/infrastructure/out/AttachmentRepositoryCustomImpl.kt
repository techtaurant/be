package com.techtaurant.mainserver.attachment.infrastructure.out

import com.github.f4b6a3.uuid.UuidCreator
import com.techtaurant.mainserver.attachment.entity.Attachment
import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.attachment.enums.AttachmentStatus
import com.techtaurant.mainserver.jooq.tables.Attachments.Companion.ATTACHMENTS
import com.techtaurant.mainserver.jooq.tables.records.AttachmentsRecord
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

@Repository
class AttachmentRepositoryCustomImpl(
    private val dsl: DSLContext,
) : AttachmentRepository {
    override fun save(attachment: Attachment): Attachment {
        val id = attachment.id ?: UuidCreator.getTimeOrderedEpoch().also { attachment.id = it }
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC)
        dsl.insertInto(ATTACHMENTS)
            .set(ATTACHMENTS.ID, id)
            .set(ATTACHMENTS.REFERENCE_ID, attachment.referenceId)
            .set(ATTACHMENTS.REFERENCE_TYPE, enumValue("attachment_reference_type", attachment.referenceType.name))
            .set(ATTACHMENTS.OBJECT_KEY, attachment.objectKey)
            .set(ATTACHMENTS.STATUS, enumValue("attachment_status", attachment.status.name))
            .set(ATTACHMENTS.ORIGINAL_FILE_NAME, attachment.originalFileName)
            .set(ATTACHMENTS.CONTENT_TYPE, attachment.contentType)
            .set(ATTACHMENTS.FILE_SIZE, attachment.fileSize)
            .set(ATTACHMENTS.CREATED_AT_UTC, attachment.createdAt.atOffset(ZoneOffset.UTC))
            .set(ATTACHMENTS.UPDATED_AT_UTC, now)
            .onConflict(ATTACHMENTS.ID)
            .doUpdate()
            .set(ATTACHMENTS.REFERENCE_ID, attachment.referenceId)
            .set(ATTACHMENTS.REFERENCE_TYPE, enumValue("attachment_reference_type", attachment.referenceType.name))
            .set(ATTACHMENTS.STATUS, enumValue("attachment_status", attachment.status.name))
            .set(ATTACHMENTS.OBJECT_KEY, attachment.objectKey)
            .set(ATTACHMENTS.ORIGINAL_FILE_NAME, attachment.originalFileName)
            .set(ATTACHMENTS.CONTENT_TYPE, attachment.contentType)
            .set(ATTACHMENTS.FILE_SIZE, attachment.fileSize)
            .set(ATTACHMENTS.UPDATED_AT_UTC, now)
            .execute()
        attachment.updatedAt = now.toInstant()
        return attachment
    }

    override fun saveAll(attachments: Iterable<Attachment>): List<Attachment> = attachments.map(::save)

    override fun deleteAll(attachments: Iterable<Attachment>) {
        val ids = attachments.mapNotNull(Attachment::id)
        if (ids.isNotEmpty()) dsl.deleteFrom(ATTACHMENTS).where(ATTACHMENTS.ID.`in`(ids)).execute()
    }

    override fun deleteAllInBatch() {
        dsl.deleteFrom(ATTACHMENTS).execute()
    }

    override fun existsById(id: UUID): Boolean = dsl.fetchExists(ATTACHMENTS, ATTACHMENTS.ID.eq(id))

    override fun deleteAllByReferenceIdAndReferenceType(
        referenceId: UUID,
        referenceType: AttachmentReferenceType,
    ) {
        dsl.deleteFrom(ATTACHMENTS).where(referenceCondition(referenceId, referenceType)).execute()
    }

    override fun findAllById(ids: Iterable<UUID>): List<Attachment> {
        val attachmentIds = ids.toList()
        return if (attachmentIds.isEmpty()) emptyList() else fetchAttachments(ATTACHMENTS.ID.`in`(attachmentIds))
    }

    override fun findAllByIdForUpdate(ids: Iterable<UUID>): List<Attachment> {
        val attachmentIds = ids.toSet()
        return if (attachmentIds.isEmpty()) {
            emptyList()
        } else {
            dsl.selectFrom(ATTACHMENTS)
                .where(ATTACHMENTS.ID.`in`(attachmentIds))
                .orderBy(ATTACHMENTS.ID)
                .forUpdate()
                .fetch()
                .map { record -> record.toAttachment() }
        }
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

    private fun enumValue(
        type: String,
        value: String,
    ) = DSL.field("cast({0} as $type)", String::class.java, DSL.value(value))

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
