package com.techtaurant.mainserver.post.application

import com.techtaurant.mainserver.attachment.application.AttachmentService
import com.techtaurant.mainserver.attachment.entity.Attachment
import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.attachment.enums.AttachmentStatus
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PostMetadataReadServiceTest {
    private val postRepository: PostRepository = mockk()
    private val attachmentService: AttachmentService = mockk()

    private val postMetadataReadService =
        PostMetadataReadService(
            postRepository = postRepository,
            attachmentService = attachmentService,
            postThumbnailResolver = PostThumbnailResolver(),
            defaultThumbnailUrl = "/static/images/post-thumbnail.png",
            baseUrl = "http://localhost:8080",
        )

    private lateinit var author: User

    @BeforeEach
    fun setUp() {
        author =
            User(
                name = "작성자",
                email = "writer@example.com",
                provider = OAuthProvider.GOOGLE,
                identifier = "writer-id",
                role = UserRole.USER,
                profileImageUrl = "https://example.com/profile.jpg",
            ).apply { id = UUID.randomUUID() }
    }

    @Test
    @DisplayName("metadata는 카운트와 presigned URL 데이터를 게시물 ID 순서대로 반환한다")
    fun getPostMetadata_returnsCountsAndPresignedUrlsInRequestOrder() {
        // given
        val firstPost = createPost(title = "첫 번째 게시물", viewCount = 10, likeCount = 2, commentCount = 1)
        val secondPost = createPost(title = "두 번째 게시물", viewCount = 20, likeCount = 5, commentCount = 3)
        val thumbnailAttachment = createAttachment(firstPost.id!!, "posts/${firstPost.id}/thumbnail.jpg", Instant.ofEpochMilli(1_000L))
        val bodyAttachment = createAttachment(firstPost.id!!, "posts/${firstPost.id}/body.jpg", Instant.ofEpochMilli(2_000L))
        firstPost.thumbnailImage = thumbnailAttachment.id

        every { postRepository.findPublishedPostsByIdIn(listOf(secondPost.id!!, firstPost.id!!)) } returns listOf(firstPost, secondPost)
        every {
            attachmentService.getConfirmedAttachmentsByReferenceIds(
                listOf(secondPost.id!!, firstPost.id!!),
                AttachmentReferenceType.POST,
            )
        } returns mapOf(firstPost.id!! to listOf(thumbnailAttachment, bodyAttachment))
        every {
            attachmentService.generatePresignedDownloadUrlMapByAttachments(listOf(thumbnailAttachment, bodyAttachment))
        } returns
            mapOf(
                thumbnailAttachment.id!! to "https://cdn.example.com/thumbnail.jpg",
                bodyAttachment.id!! to "https://cdn.example.com/body.jpg",
            )

        // when
        val result = postMetadataReadService.getPostMetadata(listOf(secondPost.id!!, firstPost.id!!))

        // then
        assertThat(result.map { it.postId }).containsExactly(secondPost.id, firstPost.id)
        assertThat(result[0].viewCount).isEqualTo(20)
        assertThat(result[0].thumbnailUrl).isEqualTo("http://localhost:8080/static/images/post-thumbnail.png")
        assertThat(result[1].viewCount).isEqualTo(10)
        assertThat(result[1].thumbnailUrl).isEqualTo("https://cdn.example.com/thumbnail.jpg")
        assertThat(result[1].attachmentPresignedUrls).hasSize(2)
    }

    private fun createPost(
        title: String,
        viewCount: Long,
        likeCount: Long,
        commentCount: Long,
    ): Post =
        Post(
            title = title,
            content = "본문",
            author = author,
            viewCount = viewCount,
            likeCount = likeCount,
            commentCount = commentCount,
        ).apply { id = UUID.randomUUID() }

    private fun createAttachment(
        postId: UUID,
        objectKey: String,
        createdAt: Instant,
    ): Attachment =
        Attachment(
            referenceId = postId,
            referenceType = AttachmentReferenceType.POST,
            objectKey = objectKey,
            status = AttachmentStatus.CONFIRMED,
            originalFileName = "image.jpg",
            contentType = "image/jpeg",
            fileSize = 1024,
        ).apply {
            id = UUID.randomUUID()
            this.createdAt = createdAt
        }
}
