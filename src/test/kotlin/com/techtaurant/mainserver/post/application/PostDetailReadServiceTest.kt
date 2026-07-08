package com.techtaurant.mainserver.post.application

import com.techtaurant.mainserver.attachment.application.AttachmentService
import com.techtaurant.mainserver.attachment.entity.Attachment
import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.attachment.enums.AttachmentStatus
import com.techtaurant.mainserver.common.enums.LikeStatus
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.entity.PostLikeLog
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.post.infrastructure.out.PostLikeLogRepository
import com.techtaurant.mainserver.post.infrastructure.out.PostReadLogRepository
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.application.UserProfileImageResolver
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID

class PostDetailReadServiceTest {
    private val postRepository: PostRepository = mockk()
    private val postViewLogService: PostViewLogService = mockk()
    private val postLikeLogRepository: PostLikeLogRepository = mockk()
    private val postReadLogRepository: PostReadLogRepository = mockk()
    private val attachmentService: AttachmentService = mockk()
    private val userProfileImageResolver = UserProfileImageResolver(attachmentService)

    private val postDetailReadService =
        PostDetailReadService(
            postRepository = postRepository,
            postViewLogService = postViewLogService,
            postLikeLogRepository = postLikeLogRepository,
            postReadLogRepository = postReadLogRepository,
            attachmentService = attachmentService,
            userProfileImageResolver = userProfileImageResolver,
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

        every { postViewLogService.recordView(any(), any(), any(), any()) } just runs
        every { attachmentService.generatePresignedDownloadUrlMapByReference(any(), any()) } returns emptyMap()
        every {
            attachmentService.getConfirmedAttachmentsByReferenceIds(any(), AttachmentReferenceType.USER)
        } returns emptyMap()
        every { attachmentService.generatePresignedDownloadUrlMapByAttachments(emptyList()) } returns emptyMap()
    }

    private fun createUserProfileAttachment(
        userId: UUID,
        attachmentId: UUID,
    ): Attachment =
        Attachment(
            referenceId = userId,
            referenceType = AttachmentReferenceType.USER,
            objectKey = "users/$userId/$attachmentId/profile.png",
            status = AttachmentStatus.CONFIRMED,
            originalFileName = "profile.png",
            contentType = "image/png",
            fileSize = 1024L,
        ).apply { id = attachmentId }

    @Nested
    @DisplayName("getPostDetail")
    inner class GetPostDetail {
        @Test
        @DisplayName("연결된 attachmentId와 presigned URL 매핑을 별도 필드로 반환한다")
        fun getPostDetail_attachmentReferences_returnsPresignedUrlMappings() {
            // given
            val postId = UUID.randomUUID()
            val viewerId = UUID.randomUUID()
            val attachmentId = UUID.randomUUID()
            val content = "<img src=\"$attachmentId\" />"
            val post =
                Post(
                    title = "게시물",
                    content = content,
                    author = author,
                    status = PostStatusEnum.PUBLISHED,
                ).apply { id = postId }

            every { postRepository.findPostDetailByIdForViewer(postId, viewerId) } returns post
            every { postLikeLogRepository.findByPostIdAndUserId(postId, viewerId) } returns null
            every { postReadLogRepository.existsByPostIdAndUserId(postId, viewerId) } returns true
            every {
                attachmentService.generatePresignedDownloadUrlMapByReference(
                    postId,
                    AttachmentReferenceType.POST,
                )
            } returns mapOf(attachmentId to "https://cdn.example.com/attachment.png")

            // when
            val result = postDetailReadService.getPostDetail(postId, viewerId, "127.0.0.1", "JUnit")

            // then
            assertThat(result.likeStatus).isEqualTo(LikeStatus.NONE)
            assertThat(result.isRead).isTrue()
            assertThat(result.content).isEqualTo(content)
            val attachmentPresignedUrl = result.attachmentPresignedUrls.single()
            assertThat(attachmentPresignedUrl.attachmentId).isEqualTo(attachmentId)
            assertThat(attachmentPresignedUrl.presignedUrl).isEqualTo("https://cdn.example.com/attachment.png")
            verify {
                postViewLogService.recordView(postId, viewerId, "127.0.0.1", "JUnit")
            }
        }

        @Test
        @DisplayName("비로그인 사용자는 읽음 여부를 false로 반환한다")
        fun getPostDetail_anonymousUser_returnsUnread() {
            // given
            val postId = UUID.randomUUID()
            val post =
                Post(
                    title = "게시물",
                    content = "본문",
                    author = author,
                    status = PostStatusEnum.PUBLISHED,
                ).apply { id = postId }

            every { postRepository.findPostDetailByIdForViewer(postId, null) } returns post

            // when
            val result = postDetailReadService.getPostDetail(postId, null, null, null)

            // then
            assertThat(result.isRead).isFalse()
            assertThat(result.attachmentPresignedUrls).isEmpty()
        }

        @Test
        @DisplayName("좋아요 로그가 있으면 likeStatus에 반영한다")
        fun getPostDetail_likedPost_returnsLikeStatus() {
            // given
            val postId = UUID.randomUUID()
            val viewerId = UUID.randomUUID()
            val post =
                Post(
                    title = "게시물",
                    content = "본문",
                    author = author,
                    status = PostStatusEnum.PUBLISHED,
                ).apply { id = postId }
            val likeLog = PostLikeLog(post = post, user = author, isLiked = true)

            every { postRepository.findPostDetailByIdForViewer(postId, viewerId) } returns post
            every { postLikeLogRepository.findByPostIdAndUserId(postId, viewerId) } returns likeLog
            every { postReadLogRepository.existsByPostIdAndUserId(postId, viewerId) } returns false

            // when
            val result = postDetailReadService.getPostDetail(postId, viewerId, null, null)

            // then
            assertThat(result.likeStatus).isEqualTo(LikeStatus.LIKE)
            assertThat(result.attachmentPresignedUrls).isEmpty()
        }

        @Test
        @DisplayName("서비스 프로필 이미지 attachment가 있으면 작성자 프로필 이미지에 presigned URL을 사용한다")
        fun getPostDetail_serviceProfileImageAttachment_usesPresignedAuthorProfileImageUrl() {
            val postId = UUID.randomUUID()
            val attachmentId = UUID.randomUUID()
            author.serviceProfileImageAttachmentId = attachmentId
            val profileAttachment = createUserProfileAttachment(author.id!!, attachmentId)
            val post =
                Post(
                    title = "게시물",
                    content = "본문",
                    author = author,
                    status = PostStatusEnum.PUBLISHED,
                ).apply { id = postId }

            every { postRepository.findPostDetailByIdForViewer(postId, null) } returns post
            every {
                attachmentService.getConfirmedAttachmentsByReferenceIds(listOf(author.id!!), AttachmentReferenceType.USER)
            } returns mapOf(author.id!! to listOf(profileAttachment))
            every {
                attachmentService.generatePresignedDownloadUrlMapByAttachments(listOf(profileAttachment))
            } returns mapOf(attachmentId to "https://cdn.example.com/authors/detail-author.png")

            val result = postDetailReadService.getPostDetail(postId, null, null, null)

            assertThat(result.author.profileImageUrl).isEqualTo("https://cdn.example.com/authors/detail-author.png")
        }
    }

    @Nested
    @DisplayName("getPublishedPostContentDetail")
    inner class GetPublishedPostContentDetail {
        @Test
        @DisplayName("정적 상세 조회는 조회수 기록과 presigned URL 생성 없이 콘텐츠만 반환한다")
        fun getPublishedPostContentDetail_returnsStaticContentWithoutSideEffects() {
            // given
            val postId = UUID.randomUUID()
            val post =
                Post(
                    title = "게시물",
                    content = "본문",
                    author = author,
                    status = PostStatusEnum.PUBLISHED,
                ).apply { id = postId }

            every { postRepository.findPostDetailByIdForViewer(postId, null) } returns post

            // when
            val result = postDetailReadService.getPublishedPostContentDetail(postId)

            // then
            assertThat(result.id).isEqualTo(postId)
            assertThat(result.title).isEqualTo("게시물")
            assertThat(result.author.id).isEqualTo(author.id)
            verify(exactly = 0) {
                postViewLogService.recordView(any(), any(), any(), any())
                attachmentService.generatePresignedDownloadUrlMapByReference(any(), any())
                postLikeLogRepository.findByPostIdAndUserId(any(), any())
                postReadLogRepository.existsByPostIdAndUserId(any(), any())
            }
        }
    }
}
