package com.techtaurant.mainserver.post.entity

import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

class PostReferencedAttachmentIdsTest {
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
    @DisplayName("본문에 등장한 순서대로 첨부 ID를 반환한다")
    fun referencedAttachmentIds_returnsIdsInContentOrder() {
        // given
        val firstAttachmentId = UUID.randomUUID()
        val secondAttachmentId = UUID.randomUUID()
        val post = createPost("<img src=\"$firstAttachmentId\" /><p>본문</p><img src=\"$secondAttachmentId\" />")

        // when & then
        assertThat(post.referencedAttachmentIds()).containsExactly(firstAttachmentId, secondAttachmentId)
    }

    @Test
    @DisplayName("같은 첨부가 여러 번 등장해도 한 번만 반환한다")
    fun referencedAttachmentIds_deduplicatesRepeatedReference() {
        // given
        val attachmentId = UUID.randomUUID()
        val post = createPost("<img src=\"$attachmentId\" /><img src=\"$attachmentId\" />")

        // when & then
        assertThat(post.referencedAttachmentIds()).containsExactly(attachmentId)
    }

    @Test
    @DisplayName("본문에 첨부 참조가 없으면 빈 목록을 반환한다")
    fun referencedAttachmentIds_withoutReference_returnsEmpty() {
        // given
        val post = createPost("<p>첨부가 없는 본문</p>")

        // when & then
        assertThat(post.referencedAttachmentIds()).isEmpty()
    }

    @Test
    @DisplayName("대문자 UUID 표기도 첨부 참조로 인식한다")
    fun referencedAttachmentIds_recognizesUppercaseUuid() {
        // given
        val attachmentId = UUID.randomUUID()
        val post = createPost("<img src=\"${attachmentId.toString().uppercase()}\" />")

        // when & then
        assertThat(post.referencedAttachmentIds()).containsExactly(attachmentId)
    }

    private fun createPost(content: String): Post =
        Post(
            title = "게시물",
            content = content,
            author = author,
        ).apply { id = UUID.randomUUID() }
}
