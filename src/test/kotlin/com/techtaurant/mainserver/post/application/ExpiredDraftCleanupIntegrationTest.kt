package com.techtaurant.mainserver.post.application

import com.techtaurant.mainserver.attachment.application.AttachmentService
import com.techtaurant.mainserver.attachment.application.S3StorageService
import com.techtaurant.mainserver.attachment.entity.Attachment
import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.attachment.enums.AttachmentStatus
import com.techtaurant.mainserver.attachment.infrastructure.out.AttachmentRepository
import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

@DisplayName("만료 임시저장 정리 통합 테스트")
class ExpiredDraftCleanupIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var expiredDraftCleanupService: ExpiredDraftCleanupService

    @Autowired
    private lateinit var temporaryContentCleanupScheduler: TemporaryContentCleanupScheduler

    @Autowired
    private lateinit var postListReadService: PostListReadService

    @Autowired
    private lateinit var attachmentService: AttachmentService

    @Autowired
    private lateinit var attachmentRepository: AttachmentRepository

    @Autowired
    private lateinit var postRepository: PostRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @MockitoBean
    private lateinit var s3StorageService: S3StorageService

    @Test
    @DisplayName("작성자를 가리지 않고 보관 기간이 지난 임시저장을 첨부와 함께 지운다")
    fun deleteExpiredDrafts_expiredDraftsOfDifferentAuthors_deletesDraftsWithAttachments() {
        // given - 서로 다른 작성자의 만료된 임시저장, 각각 첨부를 하나씩 소유
        val firstDraftId = createDraft(author = createUser(), updatedAt = daysAgo(21))
        val secondDraftId = createDraft(author = createUser(), updatedAt = daysAgo(21))
        val firstAttachmentId = claimTmpAttachment(firstDraftId)
        val secondAttachmentId = claimTmpAttachment(secondDraftId)

        // when
        val deletedCount = expiredDraftCleanupService.deleteExpiredDrafts(limit = 100, authorId = null)

        // then
        assertThat(deletedCount).isEqualTo(2)
        assertThat(postRepository.existsById(firstDraftId)).isFalse()
        assertThat(postRepository.existsById(secondDraftId)).isFalse()
        assertThat(attachmentRepository.existsById(firstAttachmentId)).isFalse()
        assertThat(attachmentRepository.existsById(secondAttachmentId)).isFalse()
        verify(s3StorageService).deleteObjects(anyList())
    }

    @Test
    @DisplayName("보관 기간 경계 직전에 수정된 임시저장은 남긴다")
    fun deleteExpiredDrafts_justInsideRetention_keepsDraft() {
        // given - 보관 기간 경계보다 1분 뒤에 수정된 임시저장
        val draftId = createDraft(author = createUser(), updatedAt = retentionBoundary().plusSeconds(60))

        // when
        val deletedCount = expiredDraftCleanupService.deleteExpiredDrafts(limit = 100, authorId = null)

        // then
        assertThat(deletedCount).isZero()
        assertThat(postRepository.existsById(draftId)).isTrue()
    }

    @Test
    @DisplayName("보관 기간 경계 직후에 수정된 임시저장은 지운다")
    fun deleteExpiredDrafts_justOutsideRetention_deletesDraft() {
        // given - 보관 기간 경계보다 1분 앞서 수정된 임시저장
        val draftId = createDraft(author = createUser(), updatedAt = retentionBoundary().minusSeconds(60))

        // when
        val deletedCount = expiredDraftCleanupService.deleteExpiredDrafts(limit = 100, authorId = null)

        // then
        assertThat(deletedCount).isEqualTo(1)
        assertThat(postRepository.existsById(draftId)).isFalse()
    }

    @Test
    @DisplayName("임시저장이 아닌 게시물은 보관 기간이 지나도 지우지 않는다")
    fun deleteExpiredDrafts_publishedPost_keepsPost() {
        // given
        val publishedPostId = createDraft(author = createUser(), updatedAt = daysAgo(21), status = PostStatusEnum.PUBLISHED)

        // when
        val deletedCount = expiredDraftCleanupService.deleteExpiredDrafts(limit = 100, authorId = null)

        // then
        assertThat(deletedCount).isZero()
        assertThat(postRepository.existsById(publishedPostId)).isTrue()
    }

    @Test
    @DisplayName("작성자를 지정하면 그 작성자의 임시저장만 지운다")
    fun deleteExpiredDrafts_withAuthorId_deletesOnlyThatAuthorsDrafts() {
        // given
        val targetAuthor = createUser()
        val targetDraftId = createDraft(author = targetAuthor, updatedAt = daysAgo(21))
        val otherAuthorDraftId = createDraft(author = createUser(), updatedAt = daysAgo(21))

        // when
        val deletedCount = expiredDraftCleanupService.deleteExpiredDrafts(limit = 100, authorId = targetAuthor.id)

        // then
        assertThat(deletedCount).isEqualTo(1)
        assertThat(postRepository.existsById(targetDraftId)).isFalse()
        assertThat(postRepository.existsById(otherAuthorDraftId)).isTrue()
    }

    @Test
    @DisplayName("한 번에 지우는 임시저장 수는 상한을 넘지 않는다")
    fun deleteExpiredDrafts_moreDraftsThanLimit_deletesUpToLimit() {
        // given
        val author = createUser()
        repeat(3) { createDraft(author = author, updatedAt = daysAgo(21)) }

        // when
        val deletedCount = expiredDraftCleanupService.deleteExpiredDrafts(limit = 2, authorId = null)

        // then
        assertThat(deletedCount).isEqualTo(2)
        assertThat(countDrafts()).isEqualTo(1)
    }

    @Test
    @DisplayName("썸네일로 지정된 첨부를 가진 임시저장도 외래키 위반 없이 지운다")
    fun deleteExpiredDrafts_draftWithThumbnail_deletesDraftAndThumbnailAttachment() {
        // given
        val draftId = createDraft(author = createUser(), updatedAt = daysAgo(21))
        val thumbnailAttachmentId = claimTmpAttachment(draftId)
        postRepository.updateThumbnailImage(draftId, thumbnailAttachmentId)
        backdateUpdatedAt(draftId, daysAgo(21))

        // when
        val deletedCount = expiredDraftCleanupService.deleteExpiredDrafts(limit = 100, authorId = null)

        // then
        assertThat(deletedCount).isEqualTo(1)
        assertThat(postRepository.existsById(draftId)).isFalse()
        assertThat(attachmentRepository.existsById(thumbnailAttachmentId)).isFalse()
    }

    @Test
    @DisplayName("지울 임시저장이 없으면 S3를 호출하지 않는다")
    fun deleteExpiredDrafts_noExpiredDraft_skipsStorageCall() {
        // given
        createDraft(author = createUser(), updatedAt = daysAgo(1))

        // when
        val deletedCount = expiredDraftCleanupService.deleteExpiredDrafts(limit = 100, authorId = null)

        // then
        assertThat(deletedCount).isZero()
        verify(s3StorageService, never()).deleteObjects(anyList())
    }

    @Test
    @DisplayName("S3 삭제가 실패하면 임시저장과 첨부가 그대로 남아 다음 실행이 다시 지운다")
    fun deleteExpiredDrafts_storageDeleteFails_keepsDraftAndAttachment() {
        // given
        val draftId = createDraft(author = createUser(), updatedAt = daysAgo(21))
        val attachmentId = claimTmpAttachment(draftId)
        doThrow(IllegalStateException("S3 unavailable")).`when`(s3StorageService).deleteObjects(anyList())

        // when
        assertThatThrownBy { expiredDraftCleanupService.deleteExpiredDrafts(limit = 100, authorId = null) }
            .isInstanceOf(IllegalStateException::class.java)

        // then
        assertThat(postRepository.existsById(draftId)).isTrue()
        assertThat(attachmentRepository.existsById(attachmentId)).isTrue()
    }

    @Test
    @DisplayName("임시저장 목록을 조회하면 만료된 임시저장이 그 자리에서 사라진다")
    fun getMyDrafts_expiredDraft_deletesAndExcludesFromResult() {
        // given
        val author = createUser()
        val expiredDraftId = createDraft(author = author, updatedAt = daysAgo(21))
        val recentDraftId = createDraft(author = author, updatedAt = daysAgo(1))

        // when
        val drafts = postListReadService.getMyDrafts(author.id!!, null, 10)

        // then
        assertThat(drafts.content.map { it.id }).containsExactly(recentDraftId)
        assertThat(postRepository.existsById(expiredDraftId)).isFalse()
    }

    @Test
    @DisplayName("정리가 실패해도 임시저장 목록은 그대로 응답한다")
    fun getMyDrafts_cleanupFails_stillReturnsDrafts() {
        // given
        val author = createUser()
        val expiredDraftId = createDraft(author = author, updatedAt = daysAgo(21))
        claimTmpAttachment(expiredDraftId)
        val recentDraftId = createDraft(author = author, updatedAt = daysAgo(1))
        doThrow(IllegalStateException("S3 unavailable")).`when`(s3StorageService).deleteObjects(anyList())

        // when
        val drafts = postListReadService.getMyDrafts(author.id!!, null, 10)

        // then - 정리에 실패한 만료 임시저장은 남지만 목록 자체는 내려간다
        assertThat(drafts.content.map { it.id }).containsExactlyInAnyOrder(expiredDraftId, recentDraftId)
        assertThat(postRepository.existsById(expiredDraftId)).isTrue()
    }

    @Test
    @DisplayName("정리 배치 한 번이 만료 임시저장과 소유되지 않은 만료 첨부를 함께 지운다")
    fun deleteExpiredTemporaryContent_expiredDraftAndUnclaimedAttachment_deletesBoth() {
        // given
        val draftId = createDraft(author = createUser(), updatedAt = daysAgo(21))
        val draftAttachmentId = claimTmpAttachment(draftId)
        val unclaimedAttachmentId = saveTmpAttachment(createdAt = daysAgo(21))

        // when
        temporaryContentCleanupScheduler.deleteExpiredTemporaryContent()

        // then
        assertThat(postRepository.existsById(draftId)).isFalse()
        assertThat(attachmentRepository.existsById(draftAttachmentId)).isFalse()
        assertThat(attachmentRepository.existsById(unclaimedAttachmentId)).isFalse()
    }

    private fun retentionBoundary(): Instant = daysAgo(RETENTION_DAYS)

    private fun daysAgo(days: Long): Instant = Instant.now().minus(days, ChronoUnit.DAYS)

    private fun countDrafts(): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM posts WHERE status = ?",
            Int::class.java,
            PostStatusEnum.DRAFT.name,
        )!!

    private fun backdateUpdatedAt(
        postId: UUID,
        updatedAt: Instant,
    ) {
        jdbcTemplate.update("UPDATE posts SET updated_at_utc = ? WHERE id = ?", updatedAt.atOffset(ZoneOffset.UTC), postId)
    }

    /**
     * 소유가 기록된 첨부는 업로드 시각과 무관하게 임시저장을 따라 지워집니다.
     * 첨부를 보관 기간 안쪽으로 만들어, 삭제 근거가 첨부의 만료가 아니라 임시저장의 만료임을 드러냅니다.
     */
    private fun claimTmpAttachment(draftId: UUID): UUID {
        val attachmentId = saveTmpAttachment(createdAt = daysAgo(1))
        attachmentService.claimTmpAttachments(draftId, AttachmentReferenceType.POST, listOf(attachmentId))
        return attachmentId
    }

    private fun saveTmpAttachment(createdAt: Instant): UUID =
        attachmentRepository.save(
            Attachment(
                referenceId = null,
                referenceType = AttachmentReferenceType.POST,
                objectKey = "tmp/${UUID.randomUUID()}/image.jpg",
                status = AttachmentStatus.TMP,
                originalFileName = "image.jpg",
                contentType = "image/jpeg",
                fileSize = 1024L,
            ).apply { this.createdAt = createdAt },
        ).id!!

    // users.name에 UNIQUE 제약(V21)이 있어 한 테스트에서 작성자를 둘 이상 만들려면 이름도 매번 달라야 한다.
    private fun createUser(): User {
        val uniqueId = UUID.randomUUID()

        return userRepository.save(
            User(
                name = "임시저장 작성자 $uniqueId",
                email = "draft-$uniqueId@example.com",
                provider = OAuthProvider.GOOGLE,
                identifier = "draft-$uniqueId",
                role = UserRole.USER,
                profileImageUrl = "https://example.com/profile.png",
            ),
        )
    }

    private fun createDraft(
        author: User,
        updatedAt: Instant,
        status: PostStatusEnum = PostStatusEnum.DRAFT,
    ): UUID {
        val postId =
            postRepository.save(
                Post(
                    title = "임시저장 게시물",
                    content = "본문",
                    author = author,
                    status = status,
                ),
            ).id!!
        backdateUpdatedAt(postId, updatedAt)
        return postId
    }

    companion object {
        // application.yml의 aws.s3.tmp-retention-days 기본값. 경계 케이스가 이 값을 기준으로 삼는다.
        private const val RETENTION_DAYS = 14L
    }
}
