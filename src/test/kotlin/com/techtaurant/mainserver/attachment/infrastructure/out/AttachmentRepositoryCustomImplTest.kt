package com.techtaurant.mainserver.attachment.infrastructure.out

import com.techtaurant.mainserver.attachment.entity.Attachment
import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.attachment.enums.AttachmentStatus
import com.techtaurant.mainserver.base.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

@DisplayName("첨부파일 저장 통합 테스트")
class AttachmentRepositoryCustomImplTest : IntegrationTest() {
    @Autowired
    private lateinit var attachmentRepository: AttachmentRepository

    @Test
    @DisplayName("기존 첨부파일을 저장하면 모든 변경 가능 필드와 updatedAt이 DB에 반영된다")
    fun saveExistingAttachmentPersistsAllMutableFields() {
        // Given
        val createdAt = Instant.parse("2026-07-01T00:00:00Z")
        val attachment =
            attachmentRepository.save(
                Attachment(
                    referenceType = AttachmentReferenceType.POST,
                    objectKey = "tmp/original/image.png",
                    status = AttachmentStatus.TMP,
                    originalFileName = "image.png",
                    contentType = "image/png",
                    fileSize = 1_024,
                ).apply { this.createdAt = createdAt },
            )
        val referenceId = UUID.randomUUID()
        attachment.referenceId = referenceId
        attachment.referenceType = AttachmentReferenceType.USER
        attachment.objectKey = "users/$referenceId/updated/profile.webp"
        attachment.status = AttachmentStatus.CONFIRMED
        attachment.originalFileName = "profile.webp"
        attachment.contentType = "image/webp"
        attachment.fileSize = 2_048

        // When
        val savedAttachment = attachmentRepository.save(attachment)

        // Then
        val reloadedAttachment = attachmentRepository.findAllById(listOf(savedAttachment.id!!)).single()
        assertThat(reloadedAttachment.referenceId).isEqualTo(referenceId)
        assertThat(reloadedAttachment.referenceType).isEqualTo(AttachmentReferenceType.USER)
        assertThat(reloadedAttachment.objectKey).isEqualTo("users/$referenceId/updated/profile.webp")
        assertThat(reloadedAttachment.status).isEqualTo(AttachmentStatus.CONFIRMED)
        assertThat(reloadedAttachment.originalFileName).isEqualTo("profile.webp")
        assertThat(reloadedAttachment.contentType).isEqualTo("image/webp")
        assertThat(reloadedAttachment.fileSize).isEqualTo(2_048)
        assertThat(reloadedAttachment.createdAt).isEqualTo(createdAt)
        assertThat(savedAttachment.updatedAt).isEqualTo(reloadedAttachment.updatedAt)
    }
}
