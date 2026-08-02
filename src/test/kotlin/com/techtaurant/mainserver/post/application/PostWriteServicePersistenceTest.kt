package com.techtaurant.mainserver.post.application

import com.techtaurant.mainserver.attachment.entity.Attachment
import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.attachment.enums.AttachmentStatus
import com.techtaurant.mainserver.attachment.infrastructure.out.AttachmentRepository
import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.post.dto.CreatePostRequest
import com.techtaurant.mainserver.post.dto.UpdatePostRequest
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

/**
 * 게시물 저장 경로가 응답 값뿐 아니라 DB에도 반영되는지 검증한다.
 *
 * 리포지터리를 mock으로 대체하면 저장 이후 엔티티에 대입한 값이 in-memory로만 남아도 통과하므로,
 * 이 테스트는 실제 DB에 저장한 뒤 다시 읽어 확인한다.
 */
@DisplayName("게시물 저장 영속성 통합 테스트")
class PostWriteServicePersistenceTest : IntegrationTest() {
    @Autowired
    private lateinit var postWriteService: PostWriteService

    @Autowired
    private lateinit var postRepository: PostRepository

    @Autowired
    private lateinit var attachmentRepository: AttachmentRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    private lateinit var author: User

    @BeforeEach
    fun setUpAuthor() {
        author =
            userRepository.saveAndFlush(
                User(
                    name = "작성자",
                    email = "author-${UUID.randomUUID()}@example.com",
                    provider = OAuthProvider.SYSTEM,
                    identifier = "author-${UUID.randomUUID()}",
                    role = UserRole.USER,
                    profileImageUrl = "https://example.com/author.png",
                ),
            )
    }

    @Test
    @DisplayName("지정한 thumbnailAttachmentId는 DB에 저장되어 재조회 시에도 유지된다")
    fun createPostPersistsRequestedThumbnailAttachmentId() {
        // Given
        val thumbnailAttachmentId = saveConfirmedAttachment()

        // When
        val response =
            postWriteService.createPost(
                userId = author.id!!,
                request =
                    CreatePostRequest(
                        title = "썸네일이 있는 게시물",
                        content = "본문입니다.",
                        status = PostStatusEnum.PUBLISHED,
                        thumbnailAttachmentId = thumbnailAttachmentId,
                    ),
            )

        // Then
        val reloadedPost = postRepository.findById(response.id).orElseThrow()
        assertThat(reloadedPost.thumbnailImage).isEqualTo(thumbnailAttachmentId)
        assertThat(response.updatedAt).isEqualTo(reloadedPost.updatedAt)
    }

    @Test
    @DisplayName("존재하지 않는 thumbnailAttachmentId는 FK 위반이 아니라 NOT_FOUND로 실패한다")
    fun createPostWithUnknownThumbnailAttachmentIdFailsWithNotFound() {
        // Given
        val unknownAttachmentId = UUID.randomUUID()

        // When & Then
        assertThatThrownBy {
            postWriteService.createPost(
                userId = author.id!!,
                request =
                    CreatePostRequest(
                        title = "존재하지 않는 썸네일을 가리키는 게시물",
                        content = "본문입니다.",
                        status = PostStatusEnum.PUBLISHED,
                        thumbnailAttachmentId = unknownAttachmentId,
                    ),
            )
        }.isInstanceOf(ApiException::class.java)
            .hasMessage("첨부파일을 찾을 수 없습니다")
    }

    @Test
    @DisplayName("요청한 createdAt은 DB에 저장되어 재조회 시에도 유지된다")
    fun createPostPersistsRequestedCreatedAt() {
        // Given
        val requestedCreatedAt = Instant.parse("2026-04-25T10:15:30Z")

        // When
        val response =
            postWriteService.createPost(
                userId = author.id!!,
                request =
                    CreatePostRequest(
                        title = "과거 시각으로 등록한 게시물",
                        content = "본문입니다.",
                        status = PostStatusEnum.PUBLISHED,
                        createdAt = requestedCreatedAt,
                    ),
            )

        // Then
        val reloadedPost = postRepository.findById(response.id).orElseThrow()
        assertThat(reloadedPost.createdAt).isEqualTo(requestedCreatedAt)
    }

    @Test
    @DisplayName("수정으로 교체한 썸네일은 DB에 저장되어 재조회 시에도 유지된다")
    fun updatePostPersistsReplacedThumbnailAttachmentId() {
        // Given
        val originalThumbnailAttachmentId = saveConfirmedAttachment()
        val replacedThumbnailAttachmentId = saveConfirmedAttachment()
        val createdPost =
            postWriteService.createPost(
                userId = author.id!!,
                request =
                    CreatePostRequest(
                        title = "썸네일을 교체할 게시물",
                        content = "본문입니다.",
                        status = PostStatusEnum.PUBLISHED,
                        thumbnailAttachmentId = originalThumbnailAttachmentId,
                    ),
            )

        // When
        postWriteService.updatePost(
            postId = createdPost.id,
            request = UpdatePostRequest(thumbnailAttachmentId = replacedThumbnailAttachmentId),
            userId = author.id!!,
        )

        // Then
        val reloadedPost = postRepository.findById(createdPost.id).orElseThrow()
        assertThat(reloadedPost.thumbnailImage).isEqualTo(replacedThumbnailAttachmentId)
    }

    /**
     * confirm 대상에서 제외되도록 CONFIRMED 상태로 저장해 S3 호출 없이 첨부 ID만 확보한다.
     */
    private fun saveConfirmedAttachment(): UUID {
        val attachment =
            attachmentRepository.save(
                Attachment(
                    referenceType = AttachmentReferenceType.POST,
                    objectKey = "posts/${UUID.randomUUID()}/thumbnail.png",
                    status = AttachmentStatus.CONFIRMED,
                    originalFileName = "thumbnail.png",
                    contentType = "image/png",
                    fileSize = 1024,
                ),
            )

        return attachment.id!!
    }
}
