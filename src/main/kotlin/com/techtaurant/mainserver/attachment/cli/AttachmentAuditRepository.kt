package com.techtaurant.mainserver.attachment.cli

import com.techtaurant.mainserver.attachment.enums.AttachmentStatus
import com.techtaurant.mainserver.jooq.tables.Attachments.Companion.ATTACHMENTS
import org.jooq.DSLContext
import java.util.UUID

internal data class AttachmentObjectReference(
    val attachmentId: UUID,
    val rawObjectKey: String,
)

internal interface AttachmentAuditRepository {
    fun findConfirmedBatch(
        afterId: UUID?,
        batchSize: Int,
    ): List<AttachmentObjectReference>

    fun findReferencedObjectKeys(objectKeys: Collection<String>): Set<String>
}

internal class JooqAttachmentAuditRepository(
    private val dsl: DSLContext,
    private val objectKeyNormalizer: AttachmentObjectKeyNormalizer,
) : AttachmentAuditRepository {
    override fun findConfirmedBatch(
        afterId: UUID?,
        batchSize: Int,
    ): List<AttachmentObjectReference> {
        val cursorCondition = afterId?.let(ATTACHMENTS.ID::gt) ?: org.jooq.impl.DSL.trueCondition()
        return dsl.select(ATTACHMENTS.ID, ATTACHMENTS.OBJECT_KEY)
            .from(ATTACHMENTS)
            .where(cursorCondition)
            .and(ATTACHMENTS.STATUS.cast(String::class.java).eq(AttachmentStatus.CONFIRMED.name))
            .orderBy(ATTACHMENTS.ID)
            .limit(batchSize)
            .fetch { record ->
                AttachmentObjectReference(
                    attachmentId = requireNotNull(record[ATTACHMENTS.ID]),
                    rawObjectKey = requireNotNull(record[ATTACHMENTS.OBJECT_KEY]),
                )
            }
    }

    override fun findReferencedObjectKeys(objectKeys: Collection<String>): Set<String> {
        if (objectKeys.isEmpty()) return emptySet()
        val rawRepresentations = objectKeys.flatMap(objectKeyNormalizer::databaseRepresentations)
        return dsl.select(ATTACHMENTS.OBJECT_KEY)
            .from(ATTACHMENTS)
            .where(ATTACHMENTS.OBJECT_KEY.`in`(rawRepresentations))
            .fetch(ATTACHMENTS.OBJECT_KEY)
            .mapNotNull { rawObjectKey -> rawObjectKey?.let(objectKeyNormalizer::normalize) }
            .toSet()
    }
}
