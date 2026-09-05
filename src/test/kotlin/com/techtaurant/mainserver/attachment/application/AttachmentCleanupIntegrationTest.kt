package com.techtaurant.mainserver.attachment.application

import com.techtaurant.mainserver.attachment.entity.Attachment
import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.attachment.enums.AttachmentStatus
import com.techtaurant.mainserver.attachment.infrastructure.out.AttachmentRepository
import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.common.policy.TemporaryContentRetention
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@DisplayName("미확정 첨부 정리 배치 통합 테스트")
class AttachmentCleanupIntegrationTest : IntegrationTest() {
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
    @DisplayName("임시저장이 소유를 기록한 첨부는 업로드 시각이 보관 기간을 넘겨도 배치가 지우지 않는다")
    fun deleteExpiredTmpAttachments_claimedByDraft_keepsAttachmentAndThumbnail() {
        // given - 업로드는 보관 기간 이전이지만 임시저장이 소유를 기록해 둔 첨부
        val draftPostId = createDraftPost()
        val claimedAttachmentId = saveTmpAttachment(referenceId = null, createdAt = daysAgo(20))
        attachmentService.claimTmpAttachments(
            referenceId = draftPostId,
            referenceType = AttachmentReferenceType.POST,
            attachmentIds = listOf(claimedAttachmentId),
        )
        postRepository.updateThumbnailImage(draftPostId, claimedAttachmentId)

        // when
        val deletedCount = attachmentService.deleteExpiredTmpAttachments(expirationThreshold(), 100)

        // then
        assertThat(deletedCount).isZero()
        assertThat(attachmentRepository.existsById(claimedAttachmentId)).isTrue()
        assertThat(findThumbnailImage(draftPostId)).isEqualTo(claimedAttachmentId)
    }

    @Test
    @DisplayName("어느 대상에도 소유가 기록되지 않은 만료 첨부는 배치가 지운다")
    fun deleteExpiredTmpAttachments_unclaimedAndExpired_deletesAttachment() {
        // given
        val unclaimedAttachmentId = saveTmpAttachment(referenceId = null, createdAt = daysAgo(20))

        // when
        val deletedCount = attachmentService.deleteExpiredTmpAttachments(expirationThreshold(), 100)

        // then
        assertThat(deletedCount).isEqualTo(1)
        assertThat(attachmentRepository.existsById(unclaimedAttachmentId)).isFalse()
    }

    @Test
    @DisplayName("소유가 기록되지 않았어도 보관 기간 안이면 배치가 지우지 않는다")
    fun deleteExpiredTmpAttachments_unclaimedWithinRetention_keepsAttachment() {
        // given
        val recentAttachmentId = saveTmpAttachment(referenceId = null, createdAt = daysAgo(1))

        // when
        val deletedCount = attachmentService.deleteExpiredTmpAttachments(expirationThreshold(), 100)

        // then
        assertThat(deletedCount).isZero()
        assertThat(attachmentRepository.existsById(recentAttachmentId)).isTrue()
    }

    private fun expirationThreshold(): Instant = Instant.now().minus(TemporaryContentRetention.DAYS, ChronoUnit.DAYS)

    private fun daysAgo(days: Long): Instant = Instant.now().minus(days, ChronoUnit.DAYS)

    private fun findThumbnailImage(postId: UUID): UUID? =
        jdbcTemplate.queryForObject("SELECT thumbnail_image FROM posts WHERE id = ?", UUID::class.java, postId)

    private fun saveTmpAttachment(
        referenceId: UUID?,
        createdAt: Instant,
    ): UUID =
        attachmentRepository.save(
            Attachment(
                referenceId = referenceId,
                referenceType = AttachmentReferenceType.POST,
                objectKey = "tmp/${UUID.randomUUID()}/image.jpg",
                status = AttachmentStatus.TMP,
                originalFileName = "image.jpg",
                contentType = "image/jpeg",
                fileSize = 1024L,
            ).apply { this.createdAt = createdAt },
        ).id!!

    private fun createDraftPost(): UUID {
        val author =
            userRepository.save(
                User(
                    name = "임시저장 작성자",
                    email = "draft-${UUID.randomUUID()}@example.com",
                    provider = OAuthProvider.GOOGLE,
                    identifier = "draft-${UUID.randomUUID()}",
                    role = UserRole.USER,
                    profileImageUrl = "https://example.com/profile.png",
                ),
            )

        return postRepository.save(
            Post(
                title = "임시저장 게시물",
                content = "본문",
                author = author,
                status = PostStatusEnum.DRAFT,
            ),
        ).id!!
    }
}
