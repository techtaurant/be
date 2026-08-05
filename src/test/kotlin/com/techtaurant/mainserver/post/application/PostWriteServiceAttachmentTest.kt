package com.techtaurant.mainserver.post.application

import com.techtaurant.mainserver.attachment.application.AttachmentService
import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.common.lock.DistributedLock
import com.techtaurant.mainserver.notification.application.NotificationWriteService
import com.techtaurant.mainserver.post.dto.CreatePostRequest
import com.techtaurant.mainserver.post.dto.UpdatePostRequest
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.post.infrastructure.out.CategoryRepository
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserFollowRepository
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import java.util.UUID

class PostWriteServiceAttachmentTest {
    private val postRepository: PostRepository = mockk()
    private val categoryRepository: CategoryRepository = mockk()
    private val tagWriteService: TagWriteService = mockk()
    private val userRepository: UserRepository = mockk()
    private val userFollowRepository: UserFollowRepository = mockk()
    private val distributedLock: DistributedLock = mockk()
    private val attachmentService: AttachmentService = mockk()
    private val notificationWriteService: NotificationWriteService = mockk()

    private val postWriteService =
        PostWriteService(
            postRepository = postRepository,
            categoryRepository = categoryRepository,
            tagWriteService = tagWriteService,
            userRepository = userRepository,
            userFollowRepository = userFollowRepository,
            distributedLock = distributedLock,
            attachmentService = attachmentService,
            notificationWriteService = notificationWriteService,
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

        every { userRepository.findById(author.id!!) } returns Optional.of(author)
        every { userFollowRepository.findFollowerIdsByFollowingId(author.id!!) } returns emptyList()
        every { tagWriteService.resolveTags(any()) } returns emptySet()
        every { attachmentService.confirmAttachmentsByIds(any(), any(), any()) } just runs
        every { attachmentService.deleteOrphanedAttachmentsByIds(any(), any(), any()) } just runs
        every { attachmentService.deleteAttachmentsByReference(any(), any()) } just runs
        every { postRepository.findPostByIdWithAuthorForUpdate(any()) } answers {
            postRepository.findPostByIdWithAuthor(firstArg())
        }

        every { postRepository.save(any()) } answers {
            firstArg<Post>().apply {
                if (id == null) {
                    id = UUID.randomUUID()
                }
            }
        }
        every { postRepository.delete(any()) } just runs
        every { postRepository.updateThumbnailImage(any(), any()) } returns Instant.now()
    }

    @Nested
    @DisplayName("updatePost 동시성")
    inner class UpdatePostConcurrency {
        @Test
        @DisplayName("게시물 수정은 행 잠금 조회를 사용한다")
        fun updatePost_existingPost_usesLockedLookup() {
            // given
            val postId = UUID.randomUUID()
            val post =
                Post(
                    title = "기존 제목",
                    content = "기존 본문",
                    author = author,
                    status = PostStatusEnum.PUBLISHED,
                ).apply { id = postId }
            every { postRepository.findPostByIdWithAuthor(postId) } returns post

            // when
            val response = postWriteService.updatePost(postId, UpdatePostRequest(title = "수정 제목"), author.id!!)

            // then
            assertThat(response.title).isEqualTo("수정 제목")
            verify(exactly = 1) { postRepository.findPostByIdWithAuthorForUpdate(postId) }
        }
    }

    @Nested
    @DisplayName("createPost")
    inner class CreatePost {
        @Test
        @DisplayName("thumbnailAttachmentId를 지정하면 본문 첨부와 함께 confirm하고 thumbnailImage로 저장한다")
        fun createPost_withThumbnailAttachmentId_savesThumbnailImage() {
            // given
            val attachmentId = UUID.randomUUID()
            val thumbnailAttachmentId = UUID.randomUUID()
            val thumbnailUpdatedAt = Instant.parse("2026-08-01T00:00:00Z")
            var savedPost: Post? = null
            val request =
                CreatePostRequest(
                    title = "게시물",
                    content = "<p>본문</p><img src=\"$attachmentId\" />",
                    attachmentIds = listOf(attachmentId),
                    thumbnailAttachmentId = thumbnailAttachmentId,
                    status = PostStatusEnum.PUBLISHED,
                )
            every { postRepository.save(any()) } answers {
                firstArg<Post>().apply {
                    if (id == null) {
                        id = UUID.randomUUID()
                    }
                    savedPost = this
                }
            }
            every { postRepository.updateThumbnailImage(any(), any()) } returns thumbnailUpdatedAt
            // when
            val response = postWriteService.createPost(author.id!!, request)

            // then
            verify {
                attachmentService.confirmAttachmentsByIds(
                    response.id,
                    AttachmentReferenceType.POST,
                    listOf(attachmentId, thumbnailAttachmentId),
                )
            }
            assertThat(savedPost?.thumbnailImage).isEqualTo(thumbnailAttachmentId)
            assertThat(response.updatedAt).isEqualTo(thumbnailUpdatedAt)
        }

        @Test
        @DisplayName("발행 게시물 본문의 attachmentId를 확정하고 본문은 그대로 유지한다")
        fun createPost_publishedPost_confirmsAttachmentIdsWithoutRewritingContent() {
            // given
            val attachmentId = UUID.randomUUID()
            val request =
                CreatePostRequest(
                    title = "게시물",
                    content = "<p>본문</p><img src=\"$attachmentId\" />",
                    attachmentIds = listOf(attachmentId),
                    status = PostStatusEnum.PUBLISHED,
                )

            // when
            val response = postWriteService.createPost(author.id!!, request)

            // then
            verify {
                attachmentService.confirmAttachmentsByIds(
                    response.id,
                    AttachmentReferenceType.POST,
                    listOf(attachmentId),
                )
            }
            assertThat(response.content).contains(attachmentId.toString())
        }

        @Test
        @DisplayName("요청 attachmentIds에만 있고 본문에 없는 첨부는 확정하지 않는다")
        fun createPost_requestAttachmentIdsOnly_doesNotConfirmMissingContentIds() {
            // given
            val attachmentId = UUID.randomUUID()
            val request =
                CreatePostRequest(
                    title = "게시물",
                    content = "<p>본문</p>",
                    attachmentIds = listOf(attachmentId),
                    status = PostStatusEnum.PUBLISHED,
                )

            // when
            val response = postWriteService.createPost(author.id!!, request)

            // then
            verify {
                attachmentService.confirmAttachmentsByIds(
                    response.id,
                    AttachmentReferenceType.POST,
                    emptyList(),
                )
            }
        }

        @Test
        @DisplayName("attachmentIds 없이 본문에 ID가 있어도 확정하지 않는다")
        fun createPost_withoutRequestAttachmentIds_doesNotConfirmFromContentOnly() {
            // given
            val attachmentId = UUID.randomUUID()
            val request =
                CreatePostRequest(
                    title = "게시물",
                    content = "![이미지]($attachmentId)",
                    status = PostStatusEnum.PUBLISHED,
                )

            // when
            val response = postWriteService.createPost(author.id!!, request)

            // then
            verify {
                attachmentService.confirmAttachmentsByIds(
                    response.id,
                    AttachmentReferenceType.POST,
                    emptyList(),
                )
            }
            assertThat(response.content).contains(attachmentId.toString())
        }
    }

    @Nested
    @DisplayName("updatePost")
    inner class UpdatePost {
        @Test
        @DisplayName("thumbnailAttachmentId가 없으면 기존 thumbnailImage를 우선 유지한다")
        fun updatePost_withoutThumbnailAttachmentId_keepsCurrentThumbnailImage() {
            // given
            val postId = UUID.randomUUID()
            val currentThumbnailAttachmentId = UUID.randomUUID()
            val otherAttachmentId = UUID.randomUUID()
            val post =
                Post(
                    title = "기존 제목",
                    content = "기존 본문",
                    author = author,
                    thumbnailImage = currentThumbnailAttachmentId,
                    status = PostStatusEnum.PUBLISHED,
                ).apply { id = postId }

            every { postRepository.findPostByIdWithAuthor(postId) } returns post

            val request =
                UpdatePostRequest(
                    content = "<img src=\"$currentThumbnailAttachmentId\" /><img src=\"$otherAttachmentId\" />",
                    attachmentIds = listOf(currentThumbnailAttachmentId, otherAttachmentId),
                    status = PostStatusEnum.PUBLISHED,
                )

            // when
            postWriteService.updatePost(postId, request, author.id!!)

            // then
            assertThat(post.thumbnailImage).isEqualTo(currentThumbnailAttachmentId)
            verify {
                attachmentService.deleteOrphanedAttachmentsByIds(
                    postId,
                    AttachmentReferenceType.POST,
                    listOf(currentThumbnailAttachmentId, otherAttachmentId),
                )
            }
        }

        @Test
        @DisplayName("thumbnailAttachmentId가 없어도 기존 thumbnailImage가 본문에 없으면 orphan으로 정리한다")
        fun updatePost_withoutThumbnailAttachmentId_deletesCurrentThumbnailWhenRemovedFromContent() {
            // given
            val postId = UUID.randomUUID()
            val currentThumbnailAttachmentId = UUID.randomUUID()
            val remainingAttachmentId = UUID.randomUUID()
            val post =
                Post(
                    title = "기존 제목",
                    content = "기존 본문",
                    author = author,
                    thumbnailImage = currentThumbnailAttachmentId,
                    status = PostStatusEnum.PUBLISHED,
                ).apply { id = postId }

            every { postRepository.findPostByIdWithAuthor(postId) } returns post

            val request =
                UpdatePostRequest(
                    content = "<img src=\"$remainingAttachmentId\" />",
                    attachmentIds = listOf(remainingAttachmentId),
                    status = PostStatusEnum.PUBLISHED,
                )

            // when
            postWriteService.updatePost(postId, request, author.id!!)

            // then
            assertThat(post.thumbnailImage).isEqualTo(remainingAttachmentId)
            verify {
                attachmentService.confirmAttachmentsByIds(
                    postId,
                    AttachmentReferenceType.POST,
                    listOf(remainingAttachmentId),
                )
            }
            verify {
                attachmentService.deleteOrphanedAttachmentsByIds(
                    postId,
                    AttachmentReferenceType.POST,
                    listOf(remainingAttachmentId),
                )
            }
        }

        @Test
        @DisplayName("수정 시 thumbnailAttachmentId를 별도로 전달하면 본문 첨부와 함께 confirm하고 유지한다")
        fun updatePost_withSeparateThumbnailAttachment_confirmsAndKeepsThumbnail() {
            // given
            val postId = UUID.randomUUID()
            val contentAttachmentId = UUID.randomUUID()
            val thumbnailAttachmentId = UUID.randomUUID()
            val post =
                Post(
                    title = "기존 제목",
                    content = "기존 본문",
                    author = author,
                    status = PostStatusEnum.PUBLISHED,
                ).apply { id = postId }

            every { postRepository.findPostByIdWithAuthor(postId) } returns post

            val request =
                UpdatePostRequest(
                    content = "<img src=\"$contentAttachmentId\" />",
                    attachmentIds = listOf(contentAttachmentId),
                    thumbnailAttachmentId = thumbnailAttachmentId,
                    status = PostStatusEnum.PUBLISHED,
                )

            // when
            postWriteService.updatePost(postId, request, author.id!!)

            // then
            verify {
                attachmentService.confirmAttachmentsByIds(
                    postId,
                    AttachmentReferenceType.POST,
                    listOf(contentAttachmentId, thumbnailAttachmentId),
                )
            }
            verify {
                attachmentService.deleteOrphanedAttachmentsByIds(
                    postId,
                    AttachmentReferenceType.POST,
                    listOf(contentAttachmentId, thumbnailAttachmentId),
                )
            }
            assertThat(post.thumbnailImage).isEqualTo(thumbnailAttachmentId)
        }

        @Test
        @DisplayName("요청으로 받은 attachmentId 목록 기준으로 orphan 첨부를 정리한다")
        fun updatePost_attachmentIdsRequest_keepsRequestedAttachments() {
            // given
            val postId = UUID.randomUUID()
            val newAttachmentId = UUID.randomUUID()
            val post =
                Post(
                    title = "기존 제목",
                    content = "기존 본문",
                    author = author,
                    status = PostStatusEnum.PUBLISHED,
                ).apply { id = postId }

            every { postRepository.findPostByIdWithAuthor(postId) } returns post

            val request =
                UpdatePostRequest(
                    content = "<img src=\"$newAttachmentId\" />",
                    attachmentIds = listOf(newAttachmentId),
                    status = PostStatusEnum.PUBLISHED,
                )

            // when
            val response = postWriteService.updatePost(postId, request, author.id!!)

            // then
            verify {
                attachmentService.confirmAttachmentsByIds(
                    postId,
                    AttachmentReferenceType.POST,
                    listOf(newAttachmentId),
                )
            }
            verify {
                attachmentService.deleteOrphanedAttachmentsByIds(
                    postId,
                    AttachmentReferenceType.POST,
                    listOf(newAttachmentId),
                )
            }
            assertThat(response.content).contains(newAttachmentId.toString())
        }

        @Test
        @DisplayName("첨부 필드 없이 본문만 바꾸면 기존 첨부와 썸네일을 유지한다")
        fun updatePost_withoutAttachmentFields_keepsExistingAttachmentsAndThumbnail() {
            // given
            val postId = UUID.randomUUID()
            val newAttachmentId = UUID.randomUUID()
            val currentThumbnailAttachmentId = UUID.randomUUID()
            val post =
                Post(
                    title = "기존 제목",
                    content = "기존 본문",
                    author = author,
                    thumbnailImage = currentThumbnailAttachmentId,
                    status = PostStatusEnum.PUBLISHED,
                ).apply { id = postId }

            every { postRepository.findPostByIdWithAuthor(postId) } returns post

            val request =
                UpdatePostRequest(
                    content = "<img src=\"$newAttachmentId\" />",
                    status = PostStatusEnum.PUBLISHED,
                )

            // when
            val response = postWriteService.updatePost(postId, request, author.id!!)

            // then
            verify(exactly = 0) { attachmentService.confirmAttachmentsByIds(any(), any(), any()) }
            verify(exactly = 0) { attachmentService.deleteOrphanedAttachmentsByIds(any(), any(), any()) }
            assertThat(response.content).contains(newAttachmentId.toString())
            assertThat(post.thumbnailImage).isEqualTo(currentThumbnailAttachmentId)
        }

        @Test
        @DisplayName("attachmentIds를 빈 목록으로 명시하면 기존 첨부와 썸네일을 제거한다")
        fun updatePost_withEmptyAttachmentIds_removesExistingAttachmentsAndThumbnail() {
            // given
            val postId = UUID.randomUUID()
            val currentThumbnailAttachmentId = UUID.randomUUID()
            val post =
                Post(
                    title = "기존 제목",
                    content = "기존 본문",
                    author = author,
                    thumbnailImage = currentThumbnailAttachmentId,
                    status = PostStatusEnum.PUBLISHED,
                ).apply { id = postId }

            every { postRepository.findPostByIdWithAuthor(postId) } returns post

            // when
            postWriteService.updatePost(
                postId,
                UpdatePostRequest(attachmentIds = emptyList()),
                author.id!!,
            )

            // then
            verify {
                attachmentService.deleteOrphanedAttachmentsByIds(
                    postId,
                    AttachmentReferenceType.POST,
                    emptyList(),
                )
            }
            assertThat(post.thumbnailImage).isNull()
        }

        @Test
        @DisplayName("thumbnailAttachmentId만 지정하면 새 썸네일을 확정하고 기존 본문 첨부는 유지한다")
        fun updatePost_withOnlyThumbnailAttachmentId_updatesThumbnailWithoutDeletingAttachments() {
            // given
            val postId = UUID.randomUUID()
            val newThumbnailAttachmentId = UUID.randomUUID()
            val post =
                Post(
                    title = "기존 제목",
                    content = "기존 본문",
                    author = author,
                    status = PostStatusEnum.PUBLISHED,
                ).apply { id = postId }

            every { postRepository.findPostByIdWithAuthor(postId) } returns post

            // when
            postWriteService.updatePost(
                postId,
                UpdatePostRequest(thumbnailAttachmentId = newThumbnailAttachmentId),
                author.id!!,
            )

            // then
            verify {
                attachmentService.confirmAttachmentsByIds(
                    postId,
                    AttachmentReferenceType.POST,
                    listOf(newThumbnailAttachmentId),
                )
            }
            verify(exactly = 0) { attachmentService.deleteOrphanedAttachmentsByIds(any(), any(), any()) }
            assertThat(post.thumbnailImage).isEqualTo(newThumbnailAttachmentId)
        }

        @Test
        @DisplayName("수정 시 요청 attachmentIds에만 있는 첨부는 유지 대상으로 보지 않는다")
        fun updatePost_requestAttachmentIdsOnly_ignoresMissingContentIds() {
            // given
            val postId = UUID.randomUUID()
            val requestOnlyAttachmentId = UUID.randomUUID()
            val post =
                Post(
                    title = "기존 제목",
                    content = "기존 본문",
                    author = author,
                    status = PostStatusEnum.PUBLISHED,
                ).apply { id = postId }

            every { postRepository.findPostByIdWithAuthor(postId) } returns post

            val request =
                UpdatePostRequest(
                    content = "<p>본문만 수정</p>",
                    attachmentIds = listOf(requestOnlyAttachmentId),
                    status = PostStatusEnum.PUBLISHED,
                )

            // when
            postWriteService.updatePost(postId, request, author.id!!)

            // then
            verify {
                attachmentService.confirmAttachmentsByIds(
                    postId,
                    AttachmentReferenceType.POST,
                    emptyList(),
                )
            }
            verify {
                attachmentService.deleteOrphanedAttachmentsByIds(
                    postId,
                    AttachmentReferenceType.POST,
                    emptyList(),
                )
            }
        }
    }

    @Nested
    @DisplayName("deletePost")
    inner class DeletePost {
        @Test
        @DisplayName("게시물 삭제 시 연관 첨부를 먼저 정리한 뒤 게시물을 삭제한다")
        fun deletePost_existingPost_deletesAttachmentsBeforePost() {
            // given
            val postId = UUID.randomUUID()
            val thumbnailAttachmentId = UUID.randomUUID()
            val post =
                Post(
                    title = "삭제 대상",
                    content = "본문",
                    author = author,
                    thumbnailImage = thumbnailAttachmentId,
                    status = PostStatusEnum.PUBLISHED,
                ).apply { id = postId }
            every { postRepository.findPostByIdWithAuthorForUpdate(postId) } returns post

            // when
            postWriteService.deletePost(postId, author.id!!)

            // then
            verifyOrder {
                postRepository.findPostByIdWithAuthorForUpdate(postId)
                attachmentService.deleteAttachmentsByReference(postId, AttachmentReferenceType.POST)
                postRepository.delete(post)
            }
            assertThat(post.thumbnailImage).isNull()
        }
    }
}
