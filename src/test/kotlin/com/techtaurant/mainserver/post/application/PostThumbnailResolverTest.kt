package com.techtaurant.mainserver.post.application

import com.techtaurant.mainserver.attachment.entity.Attachment
import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.attachment.enums.AttachmentStatus
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PostThumbnailResolverTest {
    private val postThumbnailResolver = PostThumbnailResolver()

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
    @DisplayName("명시적으로 지정한 썸네일이 본문에 없어도 그 첨부를 사용한다")
    fun resolve_withExplicitThumbnail_usesThumbnailEvenWhenAbsentFromContent() {
        // given
        val thumbnailAttachment = createAttachment(createdAt = Instant.ofEpochMilli(3_000L))
        val bodyAttachment = createAttachment(createdAt = Instant.ofEpochMilli(1_000L))
        val post =
            createPost(content = "<img src=\"${bodyAttachment.id}\" />")
                .apply { thumbnailImage = thumbnailAttachment.id }

        // when
        val resolved = postThumbnailResolver.resolve(post, listOf(thumbnailAttachment, bodyAttachment))

        // then
        assertThat(resolved).isSameAs(thumbnailAttachment)
    }

    @Test
    @DisplayName("썸네일 지정이 없으면 업로드 순서가 아니라 본문에 먼저 등장한 첨부를 사용한다")
    fun resolve_withoutExplicitThumbnail_usesFirstAttachmentReferencedInContent() {
        // given: 나중에 업로드된 첨부가 본문에서는 먼저 등장한다
        val earlierUploadedAttachment = createAttachment(createdAt = Instant.ofEpochMilli(1_000L))
        val laterUploadedAttachment = createAttachment(createdAt = Instant.ofEpochMilli(9_000L))
        val post =
            createPost(
                content = "<img src=\"${laterUploadedAttachment.id}\" /><img src=\"${earlierUploadedAttachment.id}\" />",
            )

        // when
        val resolved =
            postThumbnailResolver.resolve(post, listOf(earlierUploadedAttachment, laterUploadedAttachment))

        // then
        assertThat(resolved).isSameAs(laterUploadedAttachment)
    }

    @Test
    @DisplayName("지정한 썸네일이 확정 첨부에 없으면 본문 첫 첨부로 대체한다")
    fun resolve_withMissingThumbnailAttachment_fallsBackToContent() {
        // given
        val bodyAttachment = createAttachment(createdAt = Instant.ofEpochMilli(1_000L))
        val post =
            createPost(content = "<img src=\"${bodyAttachment.id}\" />")
                .apply { thumbnailImage = UUID.randomUUID() }

        // when
        val resolved = postThumbnailResolver.resolve(post, listOf(bodyAttachment))

        // then
        assertThat(resolved).isSameAs(bodyAttachment)
    }

    @Test
    @DisplayName("본문이 참조하는 첨부가 없으면 null을 반환해 호출부가 기본 썸네일을 쓰게 한다")
    fun resolve_withoutReferencedAttachment_returnsNull() {
        // given
        val unreferencedAttachment = createAttachment(createdAt = Instant.ofEpochMilli(1_000L))
        val post = createPost(content = "<p>첨부 참조가 없는 본문</p>")

        // when
        val resolved = postThumbnailResolver.resolve(post, listOf(unreferencedAttachment))

        // then
        assertThat(resolved).isNull()
    }

    private fun createPost(content: String): Post =
        Post(
            title = "게시물",
            content = content,
            author = author,
        ).apply { id = UUID.randomUUID() }

    private fun createAttachment(createdAt: Instant): Attachment =
        Attachment(
            referenceId = UUID.randomUUID(),
            referenceType = AttachmentReferenceType.POST,
            objectKey = "posts/image.jpg",
            status = AttachmentStatus.CONFIRMED,
            originalFileName = "image.jpg",
            contentType = "image/jpeg",
            fileSize = 1024,
        ).apply {
            id = UUID.randomUUID()
            this.createdAt = createdAt
        }
}
