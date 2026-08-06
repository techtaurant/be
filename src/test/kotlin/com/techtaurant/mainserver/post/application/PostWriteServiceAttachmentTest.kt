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
        @DisplayName("thumbnailAttachmentId가 없으면 기존 thumbnailImage가 본문에서 빠져도 유지한다")
        fun updatePost_withoutThumbnailAttachmentId_keepsCurrentThumbnailWhenRemovedFromContent() {
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
            assertThat(post.thumbnailImage).isEqualTo(currentThumbnailAttachmentId)
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
                    listOf(remainingAttachmentId, currentThumbnailAttachmentId),
                )
            }
        }

        @Test
        @DisplayName("본문 첨부와 thumbnailAttachmentId를 함께 전달하면 한 번의 confirm으로 처리하고 유지한다")
        fun updatePost_withAttachmentIdsAndThumbnailAttachmentId_confirmsInSingleCall() {
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
            // 나눠 호출하면 앞선 호출이 S3를 바꾼 뒤 뒤 호출의 검증이 실패할 수 있으므로 한 번에 넘긴다.
            verify(exactly = 1) {
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
        @DisplayName("attachmentIds에서 빠져도 본문에 참조가 남아 있으면 기존 첨부를 유지한다")
        fun updatePost_attachmentIdsOmittingReferencedAttachment_keepsItByContent() {
            // given
            val postId = UUID.randomUUID()
            val existingAttachmentId = UUID.randomUUID()
            val newAttachmentId = UUID.randomUUID()
            val post =
                Post(
                    title = "기존 제목",
                    content = "<img src=\"$existingAttachmentId\" />",
                    author = author,
                    status = PostStatusEnum.PUBLISHED,
                ).apply { id = postId }

            every { postRepository.findPostByIdWithAuthor(postId) } returns post

            // when: 새로 추가한 첨부만 attachmentIds에 담고 기존 첨부는 본문에만 남긴다
            postWriteService.updatePost(
                postId,
                UpdatePostRequest(
                    content = "<img src=\"$existingAttachmentId\" /><img src=\"$newAttachmentId\" />",
                    attachmentIds = listOf(newAttachmentId),
                ),
                author.id!!,
            )

            // then
            verify {
                attachmentService.deleteOrphanedAttachmentsByIds(
                    postId,
                    AttachmentReferenceType.POST,
                    listOf(existingAttachmentId, newAttachmentId),
                )
            }
            verify {
                attachmentService.confirmAttachmentsByIds(
                    postId,
                    AttachmentReferenceType.POST,
                    listOf(newAttachmentId),
                )
            }
        }

        @Test
        @DisplayName("attachmentIds로 보내도 본문에 참조가 없으면 확정하지 않고 유지 대상에서 제외한다")
        fun updatePost_attachmentIdsAbsentFromContent_isNeitherConfirmedNorKept() {
            // given
            val postId = UUID.randomUUID()
            val unreferencedAttachmentId = UUID.randomUUID()
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
                UpdatePostRequest(
                    content = "<p>첨부를 넣지 않은 본문</p>",
                    attachmentIds = listOf(unreferencedAttachmentId),
                ),
                author.id!!,
            )

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

        @Test
        @DisplayName("본문에만 있고 attachmentIds가 없으면 확정하지 않은 채 유지 대상으로만 둔다")
        fun updatePost_attachmentOnlyInContent_isKeptWithoutConfirm() {
            // given
            val postId = UUID.randomUUID()
            val unconfirmedAttachmentId = UUID.randomUUID()
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
                UpdatePostRequest(content = "<img src=\"$unconfirmedAttachmentId\" />"),
                author.id!!,
            )

            // then
            verify(exactly = 0) { attachmentService.confirmAttachmentsByIds(any(), any(), any()) }
            verify {
                attachmentService.deleteOrphanedAttachmentsByIds(
                    postId,
                    AttachmentReferenceType.POST,
                    listOf(unconfirmedAttachmentId),
                )
            }
        }

        @Test
        @DisplayName("첨부 필드 없이 본문에서 참조를 제거하면 기존 본문 첨부를 삭제하고 썸네일은 유지한다")
        fun updatePost_withoutAttachmentFields_removesUnreferencedBodyAttachmentAndKeepsThumbnail() {
            // given
            val postId = UUID.randomUUID()
            val currentBodyAttachmentId = UUID.randomUUID()
            val currentThumbnailAttachmentId = UUID.randomUUID()
            val post =
                Post(
                    title = "기존 제목",
                    content = "<img src=\"$currentBodyAttachmentId\" />",
                    author = author,
                    thumbnailImage = currentThumbnailAttachmentId,
                    status = PostStatusEnum.PUBLISHED,
                ).apply { id = postId }

            every { postRepository.findPostByIdWithAuthor(postId) } returns post

            val request =
                UpdatePostRequest(
                    content = "<p>첨부 참조를 제거한 본문</p>",
                    status = PostStatusEnum.PUBLISHED,
                )

            // when
            val response = postWriteService.updatePost(postId, request, author.id!!)

            // then
            verify(exactly = 0) { attachmentService.confirmAttachmentsByIds(any(), any(), any()) }
            verify {
                attachmentService.deleteOrphanedAttachmentsByIds(
                    postId,
                    AttachmentReferenceType.POST,
                    listOf(currentThumbnailAttachmentId),
                )
            }
            assertThat(response.content).doesNotContain(currentBodyAttachmentId.toString())
            assertThat(post.thumbnailImage).isEqualTo(currentThumbnailAttachmentId)
        }

        @Test
        @DisplayName("첨부 필드 없이 본문 참조를 유지하면 기존 본문 첨부와 썸네일을 유지한다")
        fun updatePost_withoutAttachmentFields_keepsReferencedBodyAttachmentAndThumbnail() {
            // given
            val postId = UUID.randomUUID()
            val currentBodyAttachmentId = UUID.randomUUID()
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
                UpdatePostRequest(content = "<img src=\"$currentBodyAttachmentId\" />"),
                author.id!!,
            )

            // then
            verify(exactly = 0) { attachmentService.confirmAttachmentsByIds(any(), any(), any()) }
            verify {
                attachmentService.deleteOrphanedAttachmentsByIds(
                    postId,
                    AttachmentReferenceType.POST,
                    listOf(currentBodyAttachmentId, currentThumbnailAttachmentId),
                )
            }
            assertThat(post.thumbnailImage).isEqualTo(currentThumbnailAttachmentId)
        }

        @Test
        @DisplayName("attachmentIds를 빈 목록으로 명시해도 생략한 썸네일은 유지한다")
        fun updatePost_withEmptyAttachmentIds_keepsOmittedThumbnail() {
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
                    listOf(currentThumbnailAttachmentId),
                )
            }
            assertThat(post.thumbnailImage).isEqualTo(currentThumbnailAttachmentId)
        }

        @Test
        @DisplayName("DRAFT 상태만 지정하면 생략한 첨부와 썸네일은 유지한다")
        fun updatePost_withDraftStatusOnly_keepsOmittedAttachmentsAndThumbnail() {
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
                UpdatePostRequest(status = PostStatusEnum.DRAFT),
                author.id!!,
            )

            // then
            assertThat(post.status).isEqualTo(PostStatusEnum.DRAFT)
            assertThat(post.thumbnailImage).isEqualTo(currentThumbnailAttachmentId)
            verify(exactly = 0) { attachmentService.confirmAttachmentsByIds(any(), any(), any()) }
            verify(exactly = 0) { attachmentService.deleteOrphanedAttachmentsByIds(any(), any(), any()) }
        }

        @Test
        @DisplayName("DRAFT로 전환하면서 본문과 첨부를 전달해도 첨부를 확정하거나 정리하지 않는다")
        fun updatePost_toDraftWithAttachmentFields_skipsAttachmentConfirmAndCleanup() {
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

            // when
            postWriteService.updatePost(
                postId,
                UpdatePostRequest(
                    content = "<img src=\"$newAttachmentId\" />",
                    attachmentIds = listOf(newAttachmentId),
                    thumbnailAttachmentId = newAttachmentId,
                    status = PostStatusEnum.DRAFT,
                ),
                author.id!!,
            )

            // then
            assertThat(post.thumbnailImage).isNull()
            verify(exactly = 0) { attachmentService.confirmAttachmentsByIds(any(), any(), any()) }
            verify(exactly = 0) { attachmentService.deleteOrphanedAttachmentsByIds(any(), any(), any()) }
        }

        @Test
        @DisplayName("이미 DRAFT인 게시물은 첨부를 전달해도 확정하거나 정리하지 않는다")
        fun updatePost_draftPostWithAttachmentFields_skipsAttachmentConfirmAndCleanup() {
            // given
            val postId = UUID.randomUUID()
            val newAttachmentId = UUID.randomUUID()
            val post =
                Post(
                    title = "기존 제목",
                    content = "기존 본문",
                    author = author,
                    status = PostStatusEnum.DRAFT,
                ).apply { id = postId }

            every { postRepository.findPostByIdWithAuthor(postId) } returns post

            // when
            postWriteService.updatePost(
                postId,
                UpdatePostRequest(
                    content = "<img src=\"$newAttachmentId\" />",
                    attachmentIds = listOf(newAttachmentId),
                ),
                author.id!!,
            )

            // then
            verify(exactly = 0) { attachmentService.confirmAttachmentsByIds(any(), any(), any()) }
            verify(exactly = 0) { attachmentService.deleteOrphanedAttachmentsByIds(any(), any(), any()) }
        }

        @Test
        @DisplayName("thumbnailAttachmentId만 지정하면 새 썸네일을 확정하고 본문 참조 첨부는 유지한 채 이전 썸네일을 정리한다")
        fun updatePost_withOnlyThumbnailAttachmentId_replacesThumbnailAndKeepsReferencedAttachments() {
            // given
            val postId = UUID.randomUUID()
            val currentBodyAttachmentId = UUID.randomUUID()
            val previousThumbnailAttachmentId = UUID.randomUUID()
            val newThumbnailAttachmentId = UUID.randomUUID()
            val post =
                Post(
                    title = "기존 제목",
                    content = "<img src=\"$currentBodyAttachmentId\" />",
                    author = author,
                    thumbnailImage = previousThumbnailAttachmentId,
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
            verify {
                attachmentService.deleteOrphanedAttachmentsByIds(
                    postId,
                    AttachmentReferenceType.POST,
                    listOf(currentBodyAttachmentId, newThumbnailAttachmentId),
                )
            }
            assertThat(post.thumbnailImage).isEqualTo(newThumbnailAttachmentId)
        }

        @Test
        @DisplayName("썸네일을 본문에 남아 있는 첨부로 교체하면 이전 썸네일만 정리 대상에서 빠진다")
        fun updatePost_replaceThumbnailWithReferencedAttachment_keepsOnlyReferencedAttachments() {
            // given
            val postId = UUID.randomUUID()
            val currentBodyAttachmentId = UUID.randomUUID()
            val previousThumbnailAttachmentId = UUID.randomUUID()
            val post =
                Post(
                    title = "기존 제목",
                    content = "<img src=\"$currentBodyAttachmentId\" />",
                    author = author,
                    thumbnailImage = previousThumbnailAttachmentId,
                    status = PostStatusEnum.PUBLISHED,
                ).apply { id = postId }

            every { postRepository.findPostByIdWithAuthor(postId) } returns post

            // when
            postWriteService.updatePost(
                postId,
                UpdatePostRequest(thumbnailAttachmentId = currentBodyAttachmentId),
                author.id!!,
            )

            // then
            verify {
                attachmentService.deleteOrphanedAttachmentsByIds(
                    postId,
                    AttachmentReferenceType.POST,
                    listOf(currentBodyAttachmentId),
                )
            }
            assertThat(post.thumbnailImage).isEqualTo(currentBodyAttachmentId)
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
