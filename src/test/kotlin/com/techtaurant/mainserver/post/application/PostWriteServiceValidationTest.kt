package com.techtaurant.mainserver.post.application

import com.techtaurant.mainserver.attachment.application.AttachmentService
import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.common.lock.DistributedLock
import com.techtaurant.mainserver.notification.application.NotificationWriteService
import com.techtaurant.mainserver.post.dto.CreatePostRequest
import com.techtaurant.mainserver.post.dto.UpdatePostRequest
import com.techtaurant.mainserver.post.entity.Category
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.enums.PostStatus
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.post.infrastructure.out.CategoryRepository
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.enums.UserStatus
import com.techtaurant.mainserver.user.infrastructure.out.UserFollowRepository
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * 게시물 쓰기 경로의 입력 검증과 카테고리 경로 해석 분기를 검증한다.
 *
 * 첨부/썸네일 동작은 [PostWriteServiceAttachmentTest], DB 반영 여부는
 * PostWriteServicePersistenceTest가 담당하고, 여기서는 상태별 필수값 규칙과
 * categoryPath 파싱 규칙만 다룬다.
 */
@DisplayName("게시물 쓰기 검증 단위 테스트")
class PostWriteServiceValidationTest {
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
        every { attachmentService.claimTmpAttachments(any(), any(), any()) } just runs
        every { attachmentService.confirmAttachmentsByIds(any(), any(), any()) } just runs
        every { attachmentService.deleteOrphanedAttachmentsByIds(any(), any(), any()) } just runs
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
        every { postRepository.updateThumbnailImage(any(), any()) } returns Instant.now()
        every { distributedLock.withLockAndTransaction<Category>(any(), any(), any()) } answers {
            thirdArg<() -> Category>().invoke()
        }
    }

    @Nested
    @DisplayName("작성자 검증")
    inner class AuthorValidation {
        @Test
        @DisplayName("존재하지 않는 사용자가 작성하면 ID_NOT_FOUND로 실패한다")
        fun createPost_withUnknownUser_throwsIdNotFound() {
            // given
            val unknownUserId = UUID.randomUUID()
            every { userRepository.findById(unknownUserId) } returns Optional.empty()

            // when & then
            assertThatThrownBy {
                postWriteService.createPost(unknownUserId, CreatePostRequest(title = "제목", content = "본문"))
            }.isInstanceOf(ApiException::class.java)
                .hasMessage(UserStatus.ID_NOT_FOUND.getDescription())
        }
    }

    @Nested
    @DisplayName("상태별 제목/본문 필수값")
    inner class RequiredFieldsByStatus {
        @Test
        @DisplayName("DRAFT는 제목과 본문이 비어 있으면 기본값을 채운다")
        fun createPost_draftWithBlankFields_fillsDefaults() {
            // given
            val request = CreatePostRequest(title = "  ", content = "", status = PostStatusEnum.DRAFT)

            // when
            val response = postWriteService.createPost(author.id!!, request)

            // then
            assertThat(response.title).isEqualTo("새 게시물")
            assertThat(response.content).isEqualTo("Empty")
            verify(exactly = 0) { attachmentService.confirmAttachmentsByIds(any(), any(), any()) }
        }

        @Test
        @DisplayName("DRAFT가 아니면 제목이 비어 있을 때 TITLE_REQUIRED로 실패한다")
        fun createPost_publishedWithBlankTitle_throwsTitleRequired() {
            // given
            val request = CreatePostRequest(title = "   ", content = "본문", status = PostStatusEnum.PUBLISHED)

            // when & then
            assertThatThrownBy { postWriteService.createPost(author.id!!, request) }
                .isInstanceOf(ApiException::class.java)
                .hasMessage(PostStatus.TITLE_REQUIRED.getDescription())
        }

        @Test
        @DisplayName("DRAFT가 아니면 본문이 비어 있을 때 CONTENT_REQUIRED로 실패한다")
        fun createPost_publishedWithBlankContent_throwsContentRequired() {
            // given
            val request = CreatePostRequest(title = "제목", content = "   ", status = PostStatusEnum.PRIVATE)

            // when & then
            assertThatThrownBy { postWriteService.createPost(author.id!!, request) }
                .isInstanceOf(ApiException::class.java)
                .hasMessage(PostStatus.CONTENT_REQUIRED.getDescription())
        }
    }

    @Nested
    @DisplayName("categoryPath 해석")
    inner class CategoryPathResolution {
        @Test
        @DisplayName("categoryPath가 없으면 카테고리를 조회하지 않고 null로 저장한다")
        fun createPost_withoutCategoryPath_savesWithoutCategory() {
            // given
            var savedPost: Post? = null
            every { postRepository.save(any()) } answers {
                firstArg<Post>().apply {
                    if (id == null) {
                        id = UUID.randomUUID()
                    }
                    savedPost = this
                }
            }

            // when
            postWriteService.createPost(author.id!!, CreatePostRequest(title = "제목", content = "본문"))

            // then
            assertThat(savedPost?.category).isNull()
            verify(exactly = 0) { categoryRepository.findByUserAndPath(any(), any()) }
        }

        @Test
        @DisplayName("구분자만 있는 categoryPath는 카테고리를 만들지 않는다")
        fun createPost_withSeparatorOnlyCategoryPath_savesWithoutCategory() {
            // given
            var savedPost: Post? = null
            every { postRepository.save(any()) } answers {
                firstArg<Post>().apply {
                    if (id == null) {
                        id = UUID.randomUUID()
                    }
                    savedPost = this
                }
            }

            // when
            postWriteService.createPost(
                author.id!!,
                CreatePostRequest(title = "제목", content = "본문", categoryPath = "///"),
            )

            // then
            assertThat(savedPost?.category).isNull()
            verify(exactly = 0) { categoryRepository.findByUserAndPath(any(), any()) }
        }

        @Test
        @DisplayName("최대 깊이를 넘는 categoryPath는 CATEGORY_DEPTH_EXCEEDED로 실패한다")
        fun createPost_withTooDeepCategoryPath_throwsDepthExceeded() {
            // given
            val request =
                CreatePostRequest(title = "제목", content = "본문", categoryPath = "a/b/c/d/e/f")

            // when & then
            assertThatThrownBy { postWriteService.createPost(author.id!!, request) }
                .isInstanceOf(ApiException::class.java)
                .hasMessage(PostStatus.CATEGORY_DEPTH_EXCEEDED.getDescription())
        }

        @Test
        @DisplayName("없는 단계는 새로 만들고 있는 단계는 재사용하며 마지막 단계를 게시물에 연결한다")
        fun createPost_withNestedCategoryPath_createsMissingLevelsOnly() {
            // given
            val existingRoot =
                Category(user = author, name = "java", path = "java", depth = 1)
                    .apply { id = UUID.randomUUID() }
            every { categoryRepository.findByUserAndPath(author, "java") } returns existingRoot
            every { categoryRepository.findByUserAndPath(author, "java/spring") } returns null
            every { categoryRepository.save(any()) } answers {
                firstArg<Category>().apply { id = UUID.randomUUID() }
            }

            var savedPost: Post? = null
            every { postRepository.save(any()) } answers {
                firstArg<Post>().apply {
                    if (id == null) {
                        id = UUID.randomUUID()
                    }
                    savedPost = this
                }
            }

            // when
            postWriteService.createPost(
                author.id!!,
                CreatePostRequest(title = "제목", content = "본문", categoryPath = "java/spring"),
            )

            // then
            val leafCategory = savedPost?.category
            assertThat(leafCategory?.path).isEqualTo("java/spring")
            assertThat(leafCategory?.name).isEqualTo("spring")
            assertThat(leafCategory?.depth).isEqualTo(2)
            assertThat(leafCategory?.parent).isEqualTo(existingRoot)
            verify(exactly = 1) { categoryRepository.save(any()) }
        }
    }

    @Nested
    @DisplayName("updatePost 권한과 상태 전환")
    inner class UpdateValidation {
        @Test
        @DisplayName("없는 게시물을 수정하면 POST_NOT_FOUND로 실패한다")
        fun updatePost_withUnknownPost_throwsPostNotFound() {
            // given
            val postId = UUID.randomUUID()
            every { postRepository.findPostByIdWithAuthor(postId) } returns null

            // when & then
            assertThatThrownBy { postWriteService.updatePost(postId, UpdatePostRequest(title = "제목"), author.id!!) }
                .isInstanceOf(ApiException::class.java)
                .hasMessage(PostStatus.POST_NOT_FOUND.getDescription())
        }

        @Test
        @DisplayName("다른 사용자의 게시물을 수정하면 CANNOT_MODIFY_OTHERS_POST로 실패한다")
        fun updatePost_withOtherAuthor_throwsCannotModifyOthersPost() {
            // given
            val post = publishedPost()
            every { postRepository.findPostByIdWithAuthor(post.id!!) } returns post

            // when & then
            assertThatThrownBy { postWriteService.updatePost(post.id!!, UpdatePostRequest(title = "제목"), UUID.randomUUID()) }
                .isInstanceOf(ApiException::class.java)
                .hasMessage(PostStatus.CANNOT_MODIFY_OTHERS_POST.getDescription())
        }

        @Test
        @DisplayName("DRAFT 상태만 변경하면 생략한 썸네일과 첨부를 유지한다")
        fun updatePost_toDraft_keepsOmittedThumbnailAndAttachments() {
            // given
            val thumbnailAttachmentId = UUID.randomUUID()
            val post = publishedPost().apply { thumbnailImage = thumbnailAttachmentId }
            every { postRepository.findPostByIdWithAuthor(post.id!!) } returns post

            // when
            postWriteService.updatePost(
                post.id!!,
                UpdatePostRequest(status = PostStatusEnum.DRAFT),
                author.id!!,
            )

            // then
            assertThat(post.status).isEqualTo(PostStatusEnum.DRAFT)
            assertThat(post.thumbnailImage).isEqualTo(thumbnailAttachmentId)
            verify(exactly = 0) { attachmentService.confirmAttachmentsByIds(any(), any(), any()) }
            verify(exactly = 0) { attachmentService.deleteOrphanedAttachmentsByIds(any(), any(), any()) }
        }

        @Test
        @DisplayName("DRAFT에서 발행으로 전환할 때 제목이 비어 있으면 TITLE_REQUIRED로 실패한다")
        fun updatePost_publishingBlankTitledDraft_throwsTitleRequired() {
            // given
            val post =
                Post(title = "  ", content = "본문", author = author, status = PostStatusEnum.DRAFT)
                    .apply { id = UUID.randomUUID() }
            every { postRepository.findPostByIdWithAuthor(post.id!!) } returns post

            // when & then
            assertThatThrownBy {
                postWriteService.updatePost(post.id!!, UpdatePostRequest(status = PostStatusEnum.PUBLISHED), author.id!!)
            }.isInstanceOf(ApiException::class.java)
                .hasMessage(PostStatus.TITLE_REQUIRED.getDescription())
        }

        @Test
        @DisplayName("DRAFT에서 발행으로 전환할 때 본문이 비어 있으면 CONTENT_REQUIRED로 실패한다")
        fun updatePost_publishingBlankContentDraft_throwsContentRequired() {
            // given
            val post =
                Post(title = "제목", content = "   ", author = author, status = PostStatusEnum.DRAFT)
                    .apply { id = UUID.randomUUID() }
            every { postRepository.findPostByIdWithAuthor(post.id!!) } returns post

            // when & then
            assertThatThrownBy {
                postWriteService.updatePost(post.id!!, UpdatePostRequest(status = PostStatusEnum.PUBLISHED), author.id!!)
            }.isInstanceOf(ApiException::class.java)
                .hasMessage(PostStatus.CONTENT_REQUIRED.getDescription())
        }

        @Test
        @DisplayName("요청에 담긴 필드만 수정하고 나머지는 유지한다")
        fun updatePost_withPartialRequest_updatesOnlyProvidedFields() {
            // given
            val post = publishedPost()
            every { postRepository.findPostByIdWithAuthor(post.id!!) } returns post
            every { categoryRepository.findByUserAndPath(author, "java") } returns
                Category(user = author, name = "java", path = "java", depth = 1).apply { id = UUID.randomUUID() }

            // when
            val response =
                postWriteService.updatePost(
                    post.id!!,
                    UpdatePostRequest(content = "수정된 본문", categoryPath = "java", tags = listOf("Spring")),
                    author.id!!,
                )

            // then
            assertThat(response.title).isEqualTo("원본 제목")
            assertThat(response.content).isEqualTo("수정된 본문")
            assertThat(response.categoryPath).isEqualTo("java")
            verify { tagWriteService.resolveTags(listOf("spring")) }
        }
    }

    @Nested
    @DisplayName("deletePost 권한")
    inner class DeleteValidation {
        @Test
        @DisplayName("없는 게시물을 삭제하면 POST_NOT_FOUND로 실패한다")
        fun deletePost_withUnknownPost_throwsPostNotFound() {
            // given
            val postId = UUID.randomUUID()
            every { postRepository.findPostByIdWithAuthorForUpdate(postId) } returns null

            // when & then
            assertThatThrownBy { postWriteService.deletePost(postId, author.id!!) }
                .isInstanceOf(ApiException::class.java)
                .hasMessage(PostStatus.POST_NOT_FOUND.getDescription())
        }

        @Test
        @DisplayName("다른 사용자의 게시물을 삭제하면 CANNOT_MODIFY_OTHERS_POST로 실패하고 첨부를 지우지 않는다")
        fun deletePost_withOtherAuthor_throwsAndKeepsAttachments() {
            // given
            val post = publishedPost()
            every { postRepository.findPostByIdWithAuthorForUpdate(post.id!!) } returns post
            every { attachmentService.deleteAttachmentsByReference(any(), any()) } just runs

            // when & then
            assertThatThrownBy { postWriteService.deletePost(post.id!!, UUID.randomUUID()) }
                .isInstanceOf(ApiException::class.java)
                .hasMessage(PostStatus.CANNOT_MODIFY_OTHERS_POST.getDescription())
            verify(exactly = 0) { attachmentService.deleteAttachmentsByReference(any(), any()) }
        }

        @Test
        @DisplayName("작성자가 삭제하면 썸네일 참조를 끊고 첨부와 게시물을 함께 지운다")
        fun deletePost_withAuthor_clearsThumbnailAndDeletesAttachments() {
            // given
            val post = publishedPost().apply { thumbnailImage = UUID.randomUUID() }
            every { postRepository.findPostByIdWithAuthorForUpdate(post.id!!) } returns post
            every { attachmentService.deleteAttachmentsByReference(any(), any()) } just runs
            every { postRepository.delete(post) } just runs

            // when
            postWriteService.deletePost(post.id!!, author.id!!)

            // then
            assertThat(post.thumbnailImage).isNull()
            verify { attachmentService.deleteAttachmentsByReference(post.id!!, AttachmentReferenceType.POST) }
            verify { postRepository.delete(post) }
        }
    }

    @Nested
    @DisplayName("팔로워 알림")
    inner class FollowerNotification {
        @Test
        @DisplayName("PUBLISHED로 생성하면 팔로워에게 알림을 보낸다")
        fun createPost_published_notifiesFollowers() {
            // given
            val followerIds = listOf(UUID.randomUUID(), UUID.randomUUID())
            every { userFollowRepository.findFollowerIdsByFollowingId(author.id!!) } returns followerIds
            every { notificationWriteService.createFollowerPostNotification(any(), any(), any()) } returns UUID.randomUUID()

            // when
            val response =
                postWriteService.createPost(
                    author.id!!,
                    CreatePostRequest(title = "제목", content = "본문", status = PostStatusEnum.PUBLISHED),
                )

            // then
            verify {
                notificationWriteService.createFollowerPostNotification(
                    actorUserId = author.id!!,
                    recipientUserIds = followerIds,
                    postId = response.id,
                )
            }
        }

        @Test
        @DisplayName("PUBLISHED가 아니면 팔로워 알림을 보내지 않는다")
        fun createPost_nonPublished_doesNotNotifyFollowers() {
            // given
            every { notificationWriteService.createFollowerPostNotification(any(), any(), any()) } returns UUID.randomUUID()

            // when
            postWriteService.createPost(
                author.id!!,
                CreatePostRequest(title = "제목", content = "본문", status = PostStatusEnum.PRIVATE),
            )

            // then
            verify(exactly = 0) { notificationWriteService.createFollowerPostNotification(any(), any(), any()) }
        }
    }

    private fun publishedPost(): Post =
        Post(
            title = "원본 제목",
            content = "원본 본문",
            author = author,
            status = PostStatusEnum.PUBLISHED,
        ).apply { id = UUID.randomUUID() }
}
