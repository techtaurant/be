package com.techtaurant.mainserver.attachment.application

import com.techtaurant.mainserver.attachment.dto.AttachmentPreviewUrlsRequest
import com.techtaurant.mainserver.attachment.dto.PresignedUrlRequest
import com.techtaurant.mainserver.attachment.entity.Attachment
import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.attachment.enums.AttachmentStatus
import com.techtaurant.mainserver.attachment.infrastructure.out.AttachmentRepository
import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.common.status.DefaultStatus
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID

class AttachmentServiceTest {
    private val attachmentRepository: AttachmentRepository = mockk()
    private val s3StorageService: S3StorageService = mockk()
    private val presignedUrlExpireMinutes = 10L

    private val attachmentService =
        AttachmentService(
            attachmentRepository = attachmentRepository,
            s3StorageService = s3StorageService,
            presignedUrlExpireMinutes = presignedUrlExpireMinutes,
        )

    private val postId = UUID.randomUUID()

    // 파괴적 S3 삭제는 커밋 이후로 미뤄지므로, 단위 테스트도 트랜잭션 동기화를 활성화해야 콜백이 등록된다.
    @BeforeEach
    fun initTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization()
    }

    @AfterEach
    fun clearTransactionSynchronization() {
        TransactionSynchronizationManager.clearSynchronization()
    }

    /** 등록된 커밋 후 콜백을 실행해 커밋 시점을 재현한다. */
    private fun triggerAfterCommit() {
        TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
    }

    private fun makeAttachment(
        objectKey: String,
        status: AttachmentStatus = AttachmentStatus.CONFIRMED,
        referenceId: UUID? = postId,
    ): Attachment =
        Attachment(
            referenceId = referenceId,
            referenceType = AttachmentReferenceType.POST,
            objectKey = objectKey,
            status = status,
            originalFileName = objectKey.substringAfterLast("/"),
            contentType = "image/jpeg",
            fileSize = 1024L,
        ).apply { id = UUID.randomUUID() }

    @Nested
    @DisplayName("issuePresignedUploadUrl")
    inner class IssuePresignedUploadUrl {
        private val request =
            PresignedUrlRequest(
                fileName = "photo.jpg",
                contentType = "image/jpeg",
                fileSize = 1024L,
                referenceType = AttachmentReferenceType.POST,
            )

        @BeforeEach
        fun setUp() {
            val attachmentSlot = slot<Attachment>()
            every { attachmentRepository.save(capture(attachmentSlot)) } answers {
                attachmentSlot.captured.apply { id = UUID.randomUUID() }
            }
            every {
                s3StorageService.generatePresignedUploadUrl(any(), any(), any())
            } returns "https://s3.example.com/presigned"
        }

        @Test
        @DisplayName("TMP 상태의 Attachment를 생성하고 Presigned URL을 반환한다")
        fun issuePresignedUploadUrl_validRequest_returnsTmpAttachmentWithPresignedUrl() {
            // given & when
            val response = attachmentService.issuePresignedUploadUrl(request)

            // then
            assertThat(response.presignedUrl).isEqualTo("https://s3.example.com/presigned")
            assertThat(response.objectKey).startsWith("tmp/")
            assertThat(response.objectKey).endsWith("photo.jpg")
            assertThat(response.attachmentId).isNotNull()
        }

        @Test
        @DisplayName("objectKey는 tmp/{uuid}/{fileName} 형식으로 생성된다")
        fun issuePresignedUploadUrl_validRequest_generatesCorrectObjectKeyFormat() {
            // given & when
            val response = attachmentService.issuePresignedUploadUrl(request)

            // then
            val parts = response.objectKey.split("/")
            assertThat(parts).hasSize(3)
            assertThat(parts[0]).isEqualTo("tmp")
            assertThat(parts[2]).isEqualTo("photo.jpg")
        }

        @Test
        @DisplayName("Presigned URL 생성 시 요청의 contentType과 만료 시간을 전달한다")
        fun issuePresignedUploadUrl_validRequest_passesCorrectParamsToS3() {
            // given & when
            attachmentService.issuePresignedUploadUrl(request)

            // then
            verify {
                s3StorageService.generatePresignedUploadUrl(
                    objectKey = match { it.startsWith("tmp/") },
                    contentType = "image/jpeg",
                    expireMinutes = presignedUrlExpireMinutes,
                )
            }
        }
    }

    @Nested
    @DisplayName("confirmAttachmentsByIds")
    inner class ConfirmAttachmentsByIds {
        private val tmpKey = "tmp/${UUID.randomUUID()}/photo.jpg"
        private val tmpAttachment = makeAttachment(tmpKey, AttachmentStatus.TMP, referenceId = null)

        @BeforeEach
        fun setUp() {
            every { attachmentRepository.findAllByIdForUpdate(any()) } answers {
                attachmentRepository.findAllById(firstArg<List<UUID>>())
            }
            every { s3StorageService.exists(any()) } returns true
            every { s3StorageService.copyObject(any(), any()) } just runs
            every { attachmentRepository.saveAll(any<List<Attachment>>()) } answers { firstArg() }
        }

        @Test
        @DisplayName("빈 attachmentId 목록이면 S3 작업을 수행하지 않는다")
        fun confirmAttachmentsByIds_emptyIds_skipsS3Calls() {
            // given & when
            attachmentService.confirmAttachmentsByIds(
                referenceId = postId,
                referenceType = AttachmentReferenceType.POST,
                attachmentIds = emptyList(),
            )

            // then
            verify(exactly = 0) { s3StorageService.copyObject(any(), any()) }
        }

        @Test
        @DisplayName("요청한 Attachment를 찾지 못하면 404 예외를 던진다")
        fun confirmAttachmentsByIds_missingAttachment_throwsNotFound() {
            // given
            val missingAttachmentId = UUID.randomUUID()
            every { attachmentRepository.findAllById(listOf(missingAttachmentId)) } returns emptyList()

            // when & then
            val exception =
                assertThrows<ApiException> {
                    attachmentService.confirmAttachmentsByIds(
                        referenceId = postId,
                        referenceType = AttachmentReferenceType.POST,
                        attachmentIds = listOf(missingAttachmentId),
                    )
                }

            assertThat(exception.status).isEqualTo(DefaultStatus.NOT_FOUND)
            assertThat(exception).hasMessage("첨부파일을 찾을 수 없습니다")
        }

        @Test
        @DisplayName("요청한 Attachment 중 일부를 찾지 못해도 404 예외를 던진다")
        fun confirmAttachmentsByIds_partiallyMissingAttachment_throwsNotFound() {
            // given
            val missingAttachmentId = UUID.randomUUID()
            every { attachmentRepository.findAllById(listOf(tmpAttachment.id!!, missingAttachmentId)) } returns listOf(tmpAttachment)

            // when & then
            val exception =
                assertThrows<ApiException> {
                    attachmentService.confirmAttachmentsByIds(
                        referenceId = postId,
                        referenceType = AttachmentReferenceType.POST,
                        attachmentIds = listOf(tmpAttachment.id!!, missingAttachmentId),
                    )
                }

            assertThat(exception.status).isEqualTo(DefaultStatus.NOT_FOUND)
            assertThat(exception).hasMessage("첨부파일을 찾을 수 없습니다")
            verify(exactly = 0) { s3StorageService.copyObject(any(), any()) }
        }

        @Test
        @DisplayName("다른 게시물에 확정된 Attachment를 요청하면 400 예외를 던진다")
        fun confirmAttachmentsByIds_attachmentConfirmedForOtherPost_throwsBadRequest() {
            // given
            val otherPostId = UUID.randomUUID()
            val foreignAttachment =
                makeAttachment("posts/$otherPostId/${UUID.randomUUID()}/photo.jpg", referenceId = otherPostId)
            every { attachmentRepository.findAllById(listOf(foreignAttachment.id!!)) } returns listOf(foreignAttachment)

            // when & then
            val exception =
                assertThrows<ApiException> {
                    attachmentService.confirmAttachmentsByIds(
                        referenceId = postId,
                        referenceType = AttachmentReferenceType.POST,
                        attachmentIds = listOf(foreignAttachment.id!!),
                    )
                }

            assertThat(exception.status).isEqualTo(DefaultStatus.BAD_REQUEST)
            assertThat(exception).hasMessage("다른 대상에 연결된 첨부파일은 사용할 수 없습니다")
            verify(exactly = 0) { s3StorageService.copyObject(any(), any()) }
        }

        @Test
        @DisplayName("다른 게시물이 claim한 TMP Attachment를 요청하면 400 예외를 던진다")
        fun confirmAttachmentsByIds_tmpAttachmentClaimedByOtherPost_throwsBadRequest() {
            // given
            val otherPostId = UUID.randomUUID()
            val claimedTmpAttachment =
                makeAttachment("tmp/${UUID.randomUUID()}/photo.jpg", AttachmentStatus.TMP, referenceId = otherPostId)
            every { attachmentRepository.findAllById(listOf(claimedTmpAttachment.id!!)) } returns listOf(claimedTmpAttachment)

            // when & then
            val exception =
                assertThrows<ApiException> {
                    attachmentService.confirmAttachmentsByIds(
                        referenceId = postId,
                        referenceType = AttachmentReferenceType.POST,
                        attachmentIds = listOf(claimedTmpAttachment.id!!),
                    )
                }

            assertThat(exception.status).isEqualTo(DefaultStatus.BAD_REQUEST)
            assertThat(exception).hasMessage("다른 대상에 연결된 첨부파일은 사용할 수 없습니다")
            verify(exactly = 0) { s3StorageService.copyObject(any(), any()) }
        }

        @Test
        @DisplayName("발급 시 지정한 대상 타입과 다른 TMP Attachment를 요청하면 400 예외를 던진다")
        fun confirmAttachmentsByIds_tmpAttachmentWithMismatchedReferenceType_throwsBadRequest() {
            // given
            val userTypeAttachment =
                makeAttachment(tmpKey, AttachmentStatus.TMP, referenceId = null).apply {
                    referenceType = AttachmentReferenceType.USER
                }
            every { attachmentRepository.findAllById(listOf(userTypeAttachment.id!!)) } returns listOf(userTypeAttachment)

            // when & then
            val exception =
                assertThrows<ApiException> {
                    attachmentService.confirmAttachmentsByIds(
                        referenceId = postId,
                        referenceType = AttachmentReferenceType.POST,
                        attachmentIds = listOf(userTypeAttachment.id!!),
                    )
                }

            assertThat(exception.status).isEqualTo(DefaultStatus.BAD_REQUEST)
            assertThat(exception).hasMessage("요청한 대상 타입과 다른 첨부파일은 사용할 수 없습니다")
            // 조용히 건너뛰고 성공하면 호출부가 확정되지 않은 ID를 썸네일 FK로 저장한다
            assertThat(userTypeAttachment.status).isEqualTo(AttachmentStatus.TMP)
            verify(exactly = 0) { s3StorageService.copyObject(any(), any()) }
            verify(exactly = 0) { attachmentRepository.saveAll(any<List<Attachment>>()) }
        }

        @Test
        @DisplayName("S3 업로드가 끝나지 않은 TMP Attachment를 요청하면 400 예외를 던진다")
        fun confirmAttachmentsByIds_tmpObjectMissingInS3_throwsBadRequest() {
            // given
            every { attachmentRepository.findAllById(listOf(tmpAttachment.id!!)) } returns listOf(tmpAttachment)
            every { s3StorageService.exists(tmpKey) } returns false

            // when & then
            val exception =
                assertThrows<ApiException> {
                    attachmentService.confirmAttachmentsByIds(
                        referenceId = postId,
                        referenceType = AttachmentReferenceType.POST,
                        attachmentIds = listOf(tmpAttachment.id!!),
                    )
                }

            assertThat(exception.status).isEqualTo(DefaultStatus.BAD_REQUEST)
            assertThat(exception).hasMessage("업로드가 완료되지 않은 첨부파일은 사용할 수 없습니다")
            // 건너뛰고 성공하면 호출부가 미확정 ID를 썸네일 FK로 저장하므로 TMP 상태가 유지되면 안 된다
            assertThat(tmpAttachment.status).isEqualTo(AttachmentStatus.TMP)
            assertThat(tmpAttachment.referenceId).isNull()
            verify(exactly = 0) { s3StorageService.copyObject(any(), any()) }
            verify(exactly = 0) { attachmentRepository.saveAll(any<List<Attachment>>()) }
        }

        @Test
        @DisplayName("여러 첨부 중 하나라도 업로드가 끝나지 않았으면 나머지도 확정하지 않는다")
        fun confirmAttachmentsByIds_oneTmpObjectMissingInS3_confirmsNothing() {
            // given
            val uploadedKey = "tmp/${UUID.randomUUID()}/uploaded.jpg"
            val uploadedAttachment = makeAttachment(uploadedKey, AttachmentStatus.TMP, referenceId = null)
            every {
                attachmentRepository.findAllById(listOf(uploadedAttachment.id!!, tmpAttachment.id!!))
            } returns listOf(uploadedAttachment, tmpAttachment)
            every { s3StorageService.exists(uploadedKey) } returns true
            every { s3StorageService.exists(tmpKey) } returns false

            // when & then
            assertThrows<ApiException> {
                attachmentService.confirmAttachmentsByIds(
                    referenceId = postId,
                    referenceType = AttachmentReferenceType.POST,
                    attachmentIds = listOf(uploadedAttachment.id!!, tmpAttachment.id!!),
                )
            }

            assertThat(uploadedAttachment.status).isEqualTo(AttachmentStatus.TMP)
            verify(exactly = 0) { s3StorageService.copyObject(any(), any()) }
        }

        @Test
        @DisplayName("이미 이 게시물에 확정된 Attachment는 그대로 통과시킨다")
        fun confirmAttachmentsByIds_attachmentAlreadyConfirmedForSamePost_passes() {
            // given
            val ownedAttachment = makeAttachment("posts/$postId/${UUID.randomUUID()}/photo.jpg", referenceId = postId)
            every { attachmentRepository.findAllById(listOf(ownedAttachment.id!!)) } returns listOf(ownedAttachment)

            // when
            attachmentService.confirmAttachmentsByIds(
                referenceId = postId,
                referenceType = AttachmentReferenceType.POST,
                attachmentIds = listOf(ownedAttachment.id!!),
            )

            // then
            assertThat(ownedAttachment.referenceId).isEqualTo(postId)
            verify(exactly = 0) { s3StorageService.copyObject(any(), any()) }
        }

        @Test
        @DisplayName("TMP 파일을 posts/{referenceId}/ 경로로 복사하고 tmp/ 원본은 남긴다")
        fun confirmAttachmentsByIds_tmpAttachment_copiesS3ObjectAndKeepsSource() {
            // given
            every { attachmentRepository.findAllById(listOf(tmpAttachment.id!!)) } returns listOf(tmpAttachment)

            // when
            attachmentService.confirmAttachmentsByIds(
                referenceId = postId,
                referenceType = AttachmentReferenceType.POST,
                attachmentIds = listOf(tmpAttachment.id!!),
            )

            // then
            val newKey = tmpAttachment.objectKey
            assertThat(newKey).startsWith("posts/$postId/")
            assertThat(newKey).endsWith("photo.jpg")

            verify { s3StorageService.copyObject(tmpKey, newKey) }
            // 커밋 전에 원본을 지우면 롤백 시 DB가 가리키는 tmp/ 객체가 사라져 재시도가 막힌다.
            verify(exactly = 0) { s3StorageService.deleteObjects(any()) }
        }

        @Test
        @DisplayName("Attachment의 status를 CONFIRMED로 변경하고 referenceId를 설정한다")
        fun confirmAttachmentsByIds_tmpAttachment_updatesStatusAndReferenceId() {
            // given
            every { attachmentRepository.findAllById(listOf(tmpAttachment.id!!)) } returns listOf(tmpAttachment)

            // when
            attachmentService.confirmAttachmentsByIds(
                referenceId = postId,
                referenceType = AttachmentReferenceType.POST,
                attachmentIds = listOf(tmpAttachment.id!!),
            )

            // then
            assertThat(tmpAttachment.status).isEqualTo(AttachmentStatus.CONFIRMED)
            assertThat(tmpAttachment.referenceId).isEqualTo(postId)
            assertThat(tmpAttachment.referenceType).isEqualTo(AttachmentReferenceType.POST)
        }

        @Test
        @DisplayName("TMP 첨부 확정은 행 잠금 조회를 사용한다")
        fun confirmAttachmentsByIds_tmpAttachment_usesLockedLookup() {
            // given
            every { attachmentRepository.findAllById(listOf(tmpAttachment.id!!)) } returns listOf(tmpAttachment)

            // when
            attachmentService.confirmAttachmentsByIds(
                referenceId = postId,
                referenceType = AttachmentReferenceType.POST,
                attachmentIds = listOf(tmpAttachment.id!!),
            )

            // then
            assertThat(tmpAttachment.status).isEqualTo(AttachmentStatus.CONFIRMED)
            assertThat(tmpAttachment.referenceId).isEqualTo(postId)
            verify(exactly = 1) { attachmentRepository.findAllByIdForUpdate(listOf(tmpAttachment.id!!)) }
        }

        @Test
        @DisplayName("USER attachment는 users/{referenceId} 경로로 이동한다")
        fun confirmAttachmentsByIds_userAttachment_movesToUsersPath() {
            val userId = UUID.randomUUID()
            val userAttachment =
                makeAttachment(tmpKey, AttachmentStatus.TMP, referenceId = null).apply {
                    referenceType = AttachmentReferenceType.USER
                }
            every { attachmentRepository.findAllById(listOf(userAttachment.id!!)) } returns listOf(userAttachment)

            attachmentService.confirmAttachmentsByIds(
                referenceId = userId,
                referenceType = AttachmentReferenceType.USER,
                attachmentIds = listOf(userAttachment.id!!),
            )

            assertThat(userAttachment.objectKey).startsWith("users/$userId/")
            assertThat(userAttachment.objectKey).endsWith("photo.jpg")
        }
    }

    @Nested
    @DisplayName("claimTmpAttachments")
    inner class ClaimTmpAttachments {
        @BeforeEach
        fun setUp() {
            every { attachmentRepository.findAllByIdForUpdate(any()) } answers {
                attachmentRepository.findAllById(firstArg<List<UUID>>())
            }
            every { attachmentRepository.updateReferenceIdByIds(any(), any()) } just runs
        }

        @Test
        @DisplayName("TMP 첨부의 소유 대상만 한 번의 UPDATE로 기록하고 상태와 tmp 경로는 그대로 둔다")
        fun claimTmpAttachments_unclaimedTmpAttachment_recordsReferenceWithoutConfirming() {
            // given
            val tmpKey = "tmp/${UUID.randomUUID()}/photo.jpg"
            val tmpAttachment = makeAttachment(tmpKey, AttachmentStatus.TMP, referenceId = null)
            every { attachmentRepository.findAllById(listOf(tmpAttachment.id!!)) } returns listOf(tmpAttachment)

            // when
            attachmentService.claimTmpAttachments(
                referenceId = postId,
                referenceType = AttachmentReferenceType.POST,
                attachmentIds = listOf(tmpAttachment.id!!),
            )

            // then
            verify(exactly = 1) { attachmentRepository.updateReferenceIdByIds(postId, listOf(tmpAttachment.id!!)) }
            assertThat(tmpAttachment.status).isEqualTo(AttachmentStatus.TMP)
            assertThat(tmpAttachment.objectKey).isEqualTo(tmpKey)
            verify(exactly = 0) { s3StorageService.copyObject(any(), any()) }
        }

        @Test
        @DisplayName("빈 목록이면 조회조차 하지 않는다")
        fun claimTmpAttachments_emptyIds_skipsLookup() {
            // when
            attachmentService.claimTmpAttachments(
                referenceId = postId,
                referenceType = AttachmentReferenceType.POST,
                attachmentIds = emptyList(),
            )

            // then
            verify(exactly = 0) { attachmentRepository.findAllByIdForUpdate(any()) }
        }

        @Test
        @DisplayName("다른 게시물이 claim한 첨부를 요청하면 400 예외를 던진다")
        fun claimTmpAttachments_attachmentClaimedByOtherPost_throwsBadRequest() {
            // given
            val otherPostId = UUID.randomUUID()
            val claimedAttachment =
                makeAttachment("tmp/${UUID.randomUUID()}/photo.jpg", AttachmentStatus.TMP, referenceId = otherPostId)
            every { attachmentRepository.findAllById(listOf(claimedAttachment.id!!)) } returns listOf(claimedAttachment)

            // when & then
            val exception =
                assertThrows<ApiException> {
                    attachmentService.claimTmpAttachments(
                        referenceId = postId,
                        referenceType = AttachmentReferenceType.POST,
                        attachmentIds = listOf(claimedAttachment.id!!),
                    )
                }

            assertThat(exception.status).isEqualTo(DefaultStatus.BAD_REQUEST)
            assertThat(exception).hasMessage("다른 대상에 연결된 첨부파일은 사용할 수 없습니다")
        }

        @Test
        @DisplayName("존재하지 않는 첨부를 요청하면 404 예외를 던진다")
        fun claimTmpAttachments_unknownAttachment_throwsNotFound() {
            // given
            val unknownAttachmentId = UUID.randomUUID()
            every { attachmentRepository.findAllById(listOf(unknownAttachmentId)) } returns emptyList()

            // when & then
            val exception =
                assertThrows<ApiException> {
                    attachmentService.claimTmpAttachments(
                        referenceId = postId,
                        referenceType = AttachmentReferenceType.POST,
                        attachmentIds = listOf(unknownAttachmentId),
                    )
                }

            assertThat(exception.status).isEqualTo(DefaultStatus.NOT_FOUND)
            assertThat(exception).hasMessage("첨부파일을 찾을 수 없습니다")
        }

        @Test
        @DisplayName("이미 이 게시물에 확정된 첨부는 그대로 통과시킨다")
        fun claimTmpAttachments_alreadyConfirmedForSameReference_keepsConfirmed() {
            // given
            val confirmedAttachment = makeAttachment("posts/$postId/${UUID.randomUUID()}/photo.jpg")
            every { attachmentRepository.findAllById(listOf(confirmedAttachment.id!!)) } returns listOf(confirmedAttachment)

            // when
            attachmentService.claimTmpAttachments(
                referenceId = postId,
                referenceType = AttachmentReferenceType.POST,
                attachmentIds = listOf(confirmedAttachment.id!!),
            )

            // then
            assertThat(confirmedAttachment.status).isEqualTo(AttachmentStatus.CONFIRMED)
            assertThat(confirmedAttachment.referenceId).isEqualTo(postId)
            verify(exactly = 0) { attachmentRepository.updateReferenceIdByIds(any(), any()) }
        }
    }

    @Nested
    @DisplayName("deleteExpiredTmpAttachments")
    inner class DeleteExpiredTmpAttachments {
        @Test
        @DisplayName("보관 기간이 지난 TMP 첨부를 DB와 S3에서 삭제한다")
        fun deleteExpiredTmpAttachments_expiredTmpAttachments_deletesRowsAndObjects() {
            // given
            val threshold = Instant.parse("2026-09-01T00:00:00Z")
            val expired = makeAttachment("tmp/${UUID.randomUUID()}/old.jpg", AttachmentStatus.TMP, referenceId = null)
            every {
                attachmentRepository.findAllUnclaimedByStatusAndCreatedAtBefore(AttachmentStatus.TMP, threshold, 100)
            } returns listOf(expired)
            every { attachmentRepository.deleteAll(any<List<Attachment>>()) } just runs
            every { s3StorageService.deleteObjects(any()) } just runs

            // when
            val deletedCount = attachmentService.deleteExpiredTmpAttachments(threshold, 100)
            triggerAfterCommit()

            // then
            assertThat(deletedCount).isEqualTo(1)
            verify { attachmentRepository.deleteAll(listOf(expired)) }
            verify { s3StorageService.deleteObjects(listOf(expired.objectKey)) }
        }

        @Test
        @DisplayName("대상이 없으면 삭제를 수행하지 않는다")
        fun deleteExpiredTmpAttachments_noExpiredAttachments_skipsDeletion() {
            // given
            val threshold = Instant.parse("2026-09-01T00:00:00Z")
            every {
                attachmentRepository.findAllUnclaimedByStatusAndCreatedAtBefore(AttachmentStatus.TMP, threshold, 100)
            } returns emptyList()

            // when
            val deletedCount = attachmentService.deleteExpiredTmpAttachments(threshold, 100)

            // then
            assertThat(deletedCount).isZero()
            verify(exactly = 0) { attachmentRepository.deleteAll(any<List<Attachment>>()) }
        }
    }

    @Nested
    @DisplayName("issueTmpPreviewUrl")
    inner class IssueTmpPreviewUrl {
        @Test
        @DisplayName("TMP 첨부파일이면 미리보기 presigned URL을 반환한다")
        fun issueTmpPreviewUrl_tmpAttachment_returnsPreviewUrl() {
            // given
            val tmpAttachment = makeAttachment("tmp/${UUID.randomUUID()}/preview.jpg", AttachmentStatus.TMP, referenceId = null)
            every { attachmentRepository.findAllById(listOf(tmpAttachment.id!!)) } returns listOf(tmpAttachment)
            every { s3StorageService.exists(tmpAttachment.objectKey) } returns true
            every {
                s3StorageService.generatePresignedDownloadUrl(tmpAttachment.objectKey, presignedUrlExpireMinutes)
            } returns "https://preview-url"

            // when
            val result = attachmentService.issueTmpPreviewUrl(tmpAttachment.id!!)

            // then
            assertThat(result.attachmentId).isEqualTo(tmpAttachment.id)
            assertThat(result.objectKey).isEqualTo(tmpAttachment.objectKey)
            assertThat(result.presignedUrl).isEqualTo("https://preview-url")
        }

        @Test
        @DisplayName("TMP 상태가 아니면 예외를 던진다")
        fun issueTmpPreviewUrl_confirmedAttachment_throwsBadRequest() {
            // given
            val confirmedAttachment = makeAttachment("posts/$postId/${UUID.randomUUID()}/preview.jpg", AttachmentStatus.CONFIRMED)
            every { attachmentRepository.findAllById(listOf(confirmedAttachment.id!!)) } returns listOf(confirmedAttachment)

            // when & then
            assertThatThrownBy { attachmentService.issueTmpPreviewUrl(confirmedAttachment.id!!) }
                .isInstanceOf(ApiException::class.java)
                .hasMessage("TMP 상태의 첨부파일만 미리보기 URL을 발급할 수 있습니다")
        }
    }

    @Nested
    @DisplayName("issueTmpPreviewUrls")
    inner class IssueTmpPreviewUrls {
        @Test
        @DisplayName("여러 TMP 첨부파일의 미리보기 presigned URL을 요청 순서대로 반환한다")
        fun issueTmpPreviewUrls_tmpAttachments_returnsPreviewUrlsInOrder() {
            // given
            val firstAttachment = makeAttachment("tmp/${UUID.randomUUID()}/first.jpg", AttachmentStatus.TMP, referenceId = null)
            val secondAttachment = makeAttachment("tmp/${UUID.randomUUID()}/second.jpg", AttachmentStatus.TMP, referenceId = null)
            every {
                attachmentRepository.findAllById(listOf(secondAttachment.id!!, firstAttachment.id!!))
            } returns listOf(firstAttachment, secondAttachment)
            every { s3StorageService.exists(firstAttachment.objectKey) } returns true
            every { s3StorageService.exists(secondAttachment.objectKey) } returns true
            every {
                s3StorageService.generatePresignedDownloadUrl(firstAttachment.objectKey, presignedUrlExpireMinutes)
            } returns "https://preview-first"
            every {
                s3StorageService.generatePresignedDownloadUrl(secondAttachment.objectKey, presignedUrlExpireMinutes)
            } returns "https://preview-second"

            // when
            val result =
                attachmentService.issueTmpPreviewUrls(
                    AttachmentPreviewUrlsRequest(listOf(secondAttachment.id!!, firstAttachment.id!!)),
                )

            // then
            assertThat(result.map { it.attachmentId }).containsExactly(secondAttachment.id!!, firstAttachment.id!!)
            assertThat(result.map { it.presignedUrl }).containsExactly("https://preview-second", "https://preview-first")
        }
    }

    @Nested
    @DisplayName("deleteAttachmentsByReference")
    inner class DeleteAttachmentsByReference {
        @Test
        @DisplayName("referenceId에 연결된 모든 첨부파일을 S3와 DB에서 삭제한다")
        fun deleteAttachmentsByReference_existingAttachments_deletesAllFromS3AndDb() {
            // given
            val attachment1 = makeAttachment("posts/$postId/uuid1/a.jpg")
            val attachment2 = makeAttachment("posts/$postId/uuid2/b.jpg")
            every {
                attachmentRepository.findAllByReferenceIdAndReferenceType(postId, AttachmentReferenceType.POST)
            } returns listOf(attachment1, attachment2)
            every { s3StorageService.deleteObjects(any()) } just runs
            every {
                attachmentRepository.deleteAllByReferenceIdAndReferenceType(postId, AttachmentReferenceType.POST)
            } just runs

            // when
            attachmentService.deleteAttachmentsByReference(postId, AttachmentReferenceType.POST)

            // then
            verify { attachmentRepository.deleteAllByReferenceIdAndReferenceType(postId, AttachmentReferenceType.POST) }
            verify(exactly = 0) { s3StorageService.deleteObjects(any()) }

            triggerAfterCommit()
            verify {
                s3StorageService.deleteObjects(
                    match { it.containsAll(listOf("posts/$postId/uuid1/a.jpg", "posts/$postId/uuid2/b.jpg")) },
                )
            }
        }

        @Test
        @DisplayName("첨부파일이 없으면 S3 삭제와 DB 삭제를 수행하지 않는다")
        fun deleteAttachmentsByReference_noAttachments_skipsAllDeletion() {
            // given
            every {
                attachmentRepository.findAllByReferenceIdAndReferenceType(postId, AttachmentReferenceType.POST)
            } returns emptyList()

            // when
            attachmentService.deleteAttachmentsByReference(postId, AttachmentReferenceType.POST)

            // then
            verify(exactly = 0) { s3StorageService.deleteObjects(any()) }
            verify(exactly = 0) {
                attachmentRepository.deleteAllByReferenceIdAndReferenceType(any(), any())
            }
        }
    }

    @Nested
    @DisplayName("deleteOrphanedAttachmentsByIds")
    inner class DeleteOrphanedAttachmentsByIds {
        @Test
        @DisplayName("keepAttachmentIds에 없는 첨부파일을 S3와 DB에서 삭제한다")
        fun deleteOrphanedAttachmentsByIds_orphanExists_deletesOrphansOnly() {
            // given
            val keepAttachment = makeAttachment("posts/$postId/uuid1/keep.jpg")
            val orphanAttachment = makeAttachment("posts/$postId/uuid2/orphan.jpg")

            every {
                attachmentRepository.findAllByReferenceIdAndReferenceTypeAndIdNotIn(
                    postId,
                    AttachmentReferenceType.POST,
                    listOf(keepAttachment.id!!),
                )
            } returns listOf(orphanAttachment)
            every { s3StorageService.deleteObjects(any()) } just runs
            every { attachmentRepository.deleteAll(any<List<Attachment>>()) } just runs

            // when
            attachmentService.deleteOrphanedAttachmentsByIds(
                postId,
                AttachmentReferenceType.POST,
                listOf(keepAttachment.id!!),
            )

            // then
            verify { attachmentRepository.deleteAll(listOf(orphanAttachment)) }
            verify(exactly = 0) { s3StorageService.deleteObjects(any()) }

            triggerAfterCommit()
            verify { s3StorageService.deleteObjects(listOf(orphanAttachment.objectKey)) }
        }

        @Test
        @DisplayName("고아 파일이 없으면 S3 삭제를 수행하지 않는다")
        fun deleteOrphanedAttachmentsByIds_noOrphans_skipsDeletion() {
            // given
            val keepAttachment = makeAttachment("posts/$postId/uuid1/keep.jpg")

            every {
                attachmentRepository.findAllByReferenceIdAndReferenceTypeAndIdNotIn(
                    postId,
                    AttachmentReferenceType.POST,
                    listOf(keepAttachment.id!!),
                )
            } returns emptyList()

            // when
            attachmentService.deleteOrphanedAttachmentsByIds(
                postId,
                AttachmentReferenceType.POST,
                listOf(keepAttachment.id!!),
            )

            // then
            verify(exactly = 0) { s3StorageService.deleteObjects(any()) }
        }

        @Test
        @DisplayName("keepAttachmentIds가 비어 있으면 reference에 연결된 첨부를 모두 orphan으로 조회한다")
        fun deleteOrphanedAttachmentsByIds_withoutKeepIds_loadsAllAttachments() {
            // given
            val orphanAttachment = makeAttachment("posts/$postId/uuid2/orphan.jpg")

            every {
                attachmentRepository.findAllByReferenceIdAndReferenceType(postId, AttachmentReferenceType.POST)
            } returns listOf(orphanAttachment)
            every { s3StorageService.deleteObjects(any()) } just runs
            every { attachmentRepository.deleteAll(any<List<Attachment>>()) } just runs

            // when
            attachmentService.deleteOrphanedAttachmentsByIds(
                postId,
                AttachmentReferenceType.POST,
                emptyList(),
            )

            // then
            verify {
                attachmentRepository.findAllByReferenceIdAndReferenceType(postId, AttachmentReferenceType.POST)
            }
            verify(exactly = 0) {
                attachmentRepository.findAllByReferenceIdAndReferenceTypeAndIdNotIn(any(), any(), any())
            }
            verify { attachmentRepository.deleteAll(listOf(orphanAttachment)) }

            triggerAfterCommit()
            verify { s3StorageService.deleteObjects(listOf(orphanAttachment.objectKey)) }
        }

        @Test
        @DisplayName("커밋 후 S3 삭제가 실패해도 예외를 호출자에게 전파하지 않는다")
        fun deleteOrphanedAttachmentsByIds_s3DeleteFailsAfterCommit_doesNotPropagateException() {
            // given
            val orphanAttachment = makeAttachment("posts/$postId/uuid2/orphan.jpg")

            every {
                attachmentRepository.findAllByReferenceIdAndReferenceType(postId, AttachmentReferenceType.POST)
            } returns listOf(orphanAttachment)
            every { attachmentRepository.deleteAll(any<List<Attachment>>()) } just runs
            every { s3StorageService.deleteObjects(any()) } throws RuntimeException("S3 unavailable")

            // when
            attachmentService.deleteOrphanedAttachmentsByIds(
                postId,
                AttachmentReferenceType.POST,
                emptyList(),
            )

            // then
            // 전파되면 DB 커밋이 끝난 요청이 실패로 보이고 클라이언트가 반영된 상태에 재시도한다.
            assertThatCode { triggerAfterCommit() }.doesNotThrowAnyException()
            verify { s3StorageService.deleteObjects(listOf(orphanAttachment.objectKey)) }
        }
    }

    @Nested
    @DisplayName("generatePresignedDownloadUrlMapByReference")
    inner class GeneratePresignedDownloadUrlMapByReference {
        @Test
        @DisplayName("연결된 CONFIRMED 첨부파일의 attachmentId → URL 맵을 반환한다")
        fun generatePresignedDownloadUrlMapByReference_confirmedAttachments_returnsUrlMap() {
            // given
            val included = makeAttachment("posts/$postId/uuid1/a.jpg", AttachmentStatus.CONFIRMED)
            val excluded = makeAttachment("posts/$postId/uuid2/b.jpg", AttachmentStatus.CONFIRMED)

            every {
                attachmentRepository.findAllByReferenceIdAndReferenceType(postId, AttachmentReferenceType.POST)
            } returns listOf(included, excluded)
            every {
                s3StorageService.generatePresignedDownloadUrl(included.objectKey, presignedUrlExpireMinutes)
            } returns "https://url1"
            every {
                s3StorageService.generatePresignedDownloadUrl(excluded.objectKey, presignedUrlExpireMinutes)
            } returns "https://url2"

            // when
            val result =
                attachmentService.generatePresignedDownloadUrlMapByReference(
                    postId,
                    AttachmentReferenceType.POST,
                )

            // then
            assertThat(result).containsKeys(included.id!!, excluded.id!!)
            assertThat(result[included.id!!]).isEqualTo("https://url1")
            assertThat(result[excluded.id!!]).isEqualTo("https://url2")
        }
    }

    @Nested
    @DisplayName("generatePresignedDownloadUrlMap")
    inner class GeneratePresignedDownloadUrlMap {
        @Test
        @DisplayName("CONFIRMED 첨부파일의 objectKey → presigned GET URL 맵을 반환한다")
        fun generatePresignedDownloadUrlMap_confirmedAttachments_returnsUrlMap() {
            // given
            val key1 = "posts/$postId/uuid1/a.jpg"
            val key2 = "posts/$postId/uuid2/b.jpg"
            val confirmed1 = makeAttachment(key1, AttachmentStatus.CONFIRMED)
            val confirmed2 = makeAttachment(key2, AttachmentStatus.CONFIRMED)

            every {
                attachmentRepository.findAllByReferenceIdAndReferenceType(postId, AttachmentReferenceType.POST)
            } returns listOf(confirmed1, confirmed2)
            every { s3StorageService.generatePresignedDownloadUrl(key1, presignedUrlExpireMinutes) } returns "https://url1"
            every { s3StorageService.generatePresignedDownloadUrl(key2, presignedUrlExpireMinutes) } returns "https://url2"

            // when
            val result = attachmentService.generatePresignedDownloadUrlMap(postId, AttachmentReferenceType.POST)

            // then
            assertThat(result).hasSize(2)
            assertThat(result[key1]).isEqualTo("https://url1")
            assertThat(result[key2]).isEqualTo("https://url2")
        }

        @Test
        @DisplayName("TMP 상태의 첨부파일은 URL 맵에 포함하지 않는다")
        fun generatePresignedDownloadUrlMap_tmpAttachment_excludedFromMap() {
            // given
            val confirmedKey = "posts/$postId/uuid1/confirmed.jpg"
            val tmpKey = "tmp/${UUID.randomUUID()}/tmp.jpg"
            val confirmed = makeAttachment(confirmedKey, AttachmentStatus.CONFIRMED)
            val tmp = makeAttachment(tmpKey, AttachmentStatus.TMP)

            every {
                attachmentRepository.findAllByReferenceIdAndReferenceType(postId, AttachmentReferenceType.POST)
            } returns listOf(confirmed, tmp)
            every { s3StorageService.generatePresignedDownloadUrl(confirmedKey, presignedUrlExpireMinutes) } returns "https://url"

            // when
            val result = attachmentService.generatePresignedDownloadUrlMap(postId, AttachmentReferenceType.POST)

            // then
            assertThat(result).hasSize(1)
            assertThat(result).containsKey(confirmedKey)
            assertThat(result).doesNotContainKey(tmpKey)
        }

        @Test
        @DisplayName("첨부파일이 없으면 빈 맵을 반환한다")
        fun generatePresignedDownloadUrlMap_noAttachments_returnsEmptyMap() {
            // given
            every {
                attachmentRepository.findAllByReferenceIdAndReferenceType(postId, AttachmentReferenceType.POST)
            } returns emptyList()

            // when
            val result = attachmentService.generatePresignedDownloadUrlMap(postId, AttachmentReferenceType.POST)

            // then
            assertThat(result).isEmpty()
            verify(exactly = 0) { s3StorageService.generatePresignedDownloadUrl(any(), any()) }
        }
    }

    @Nested
    @DisplayName("generatePresignedDownloadUrlMapByAttachments")
    inner class GeneratePresignedDownloadUrlMapByAttachments {
        @Test
        @DisplayName("전달된 CONFIRMED 첨부파일 목록으로 attachmentId → URL 맵을 만든다")
        fun generatePresignedDownloadUrlMapByAttachments_confirmedAttachments_returnsUrlMap() {
            // given
            val confirmed1 = makeAttachment("posts/$postId/uuid1/a.jpg", AttachmentStatus.CONFIRMED)
            val confirmed2 = makeAttachment("posts/$postId/uuid2/b.jpg", AttachmentStatus.CONFIRMED)
            every { s3StorageService.generatePresignedDownloadUrl(confirmed1.objectKey, presignedUrlExpireMinutes) } returns "https://url1"
            every { s3StorageService.generatePresignedDownloadUrl(confirmed2.objectKey, presignedUrlExpireMinutes) } returns "https://url2"

            // when
            val result = attachmentService.generatePresignedDownloadUrlMapByAttachments(listOf(confirmed1, confirmed2))

            // then
            assertThat(result[confirmed1.id!!]).isEqualTo("https://url1")
            assertThat(result[confirmed2.id!!]).isEqualTo("https://url2")
        }

        @Test
        @DisplayName("TMP 첨부파일은 제외한다")
        fun generatePresignedDownloadUrlMapByAttachments_tmpAttachment_excluded() {
            // given
            val confirmed = makeAttachment("posts/$postId/uuid1/a.jpg", AttachmentStatus.CONFIRMED)
            val tmp = makeAttachment("tmp/${UUID.randomUUID()}/tmp.jpg", AttachmentStatus.TMP, referenceId = null)
            every { s3StorageService.generatePresignedDownloadUrl(confirmed.objectKey, presignedUrlExpireMinutes) } returns "https://url"

            // when
            val result = attachmentService.generatePresignedDownloadUrlMapByAttachments(listOf(confirmed, tmp))

            // then
            assertThat(result).hasSize(1)
            assertThat(result).containsKey(confirmed.id!!)
            assertThat(result).doesNotContainKey(tmp.id!!)
        }
    }

    @Nested
    @DisplayName("getConfirmedAttachments")
    inner class GetConfirmedAttachments {
        @Test
        @DisplayName("CONFIRMED 상태의 첨부파일만 반환한다")
        fun getConfirmedAttachments_mixedStatuses_returnsOnlyConfirmed() {
            // given
            val confirmed = makeAttachment("posts/$postId/uuid1/a.jpg", AttachmentStatus.CONFIRMED)
            val tmp = makeAttachment("tmp/${UUID.randomUUID()}/b.jpg", AttachmentStatus.TMP)

            every {
                attachmentRepository.findAllByReferenceIdAndReferenceType(postId, AttachmentReferenceType.POST)
            } returns listOf(confirmed, tmp)

            // when
            val result = attachmentService.getConfirmedAttachments(postId, AttachmentReferenceType.POST)

            // then
            assertThat(result).hasSize(1)
            assertThat(result[0].status).isEqualTo(AttachmentStatus.CONFIRMED)
            assertThat(result[0].objectKey).isEqualTo("posts/$postId/uuid1/a.jpg")
        }

        @Test
        @DisplayName("첨부파일이 없으면 빈 목록을 반환한다")
        fun getConfirmedAttachments_noAttachments_returnsEmptyList() {
            // given
            every {
                attachmentRepository.findAllByReferenceIdAndReferenceType(postId, AttachmentReferenceType.POST)
            } returns emptyList()

            // when
            val result = attachmentService.getConfirmedAttachments(postId, AttachmentReferenceType.POST)

            // then
            assertThat(result).isEmpty()
        }
    }
}
