package com.techtaurant.mainserver.comment.application

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.comment.dto.CreateCommentRequest
import com.techtaurant.mainserver.comment.dto.UpdateCommentRequest
import com.techtaurant.mainserver.comment.entity.Comment
import com.techtaurant.mainserver.comment.enums.CommentStatus
import com.techtaurant.mainserver.comment.infrastructure.out.CommentRepository
import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.notification.enums.NotificationType
import com.techtaurant.mainserver.notification.infrastructure.out.NotificationRecipientRepository
import com.techtaurant.mainserver.notification.infrastructure.out.NotificationRepository
import com.techtaurant.mainserver.post.entity.Category
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.enums.PostStatus
import com.techtaurant.mainserver.post.infrastructure.out.CategoryRepository
import com.techtaurant.mainserver.post.infrastructure.out.PostDailyStatsRepository
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.enums.UserStatus
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Transactional
@ActiveProfiles("test")
class CommentWriteServiceTest : IntegrationTest() {
    @Autowired
    private lateinit var commentWriteService: CommentWriteService

    @Autowired
    private lateinit var commentRepository: CommentRepository

    @Autowired
    private lateinit var postRepository: PostRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var notificationRepository: NotificationRepository

    @Autowired
    private lateinit var notificationRecipientRepository: NotificationRecipientRepository

    @Autowired
    private lateinit var categoryRepository: CategoryRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var postDailyStatsRepository: PostDailyStatsRepository

    private lateinit var testUser: User
    private lateinit var testPost: Post
    private lateinit var testCategory: Category

    @BeforeEach
    fun setUpTestData() {
        testUser =
            userRepository.save(
                User(
                    name = "테스트 사용자",
                    email = "test@example.com",
                    provider = OAuthProvider.GOOGLE,
                    identifier = "test-id-${UUID.randomUUID()}",
                    role = UserRole.USER,
                    profileImageUrl = "https://example.com/profile.jpg",
                ),
            )

        testCategory =
            categoryRepository.save(
                Category(
                    user = testUser,
                    name = "테스트 카테고리",
                    path = "테스트카테고리",
                    depth = 1,
                ),
            )

        testPost =
            postRepository.save(
                Post(
                    title = "테스트 게시물",
                    content = "테스트 내용",
                    author = testUser,
                    category = testCategory,
                    commentCount = 0,
                ),
            )
    }

    @Test
    @DisplayName("댓글 작성 시 게시물의 commentCount가 원자적으로 1 증가한다")
    fun createComment_incrementsCommentCount() {
        // Given
        val initialCount = testPost.commentCount
        val request =
            CreateCommentRequest(
                postId = testPost.id!!,
                content = "테스트 댓글입니다",
                parentId = null,
            )

        // When
        commentWriteService.createComment(testUser.id!!, request)
        entityManager.flush()
        entityManager.clear()

        // Then
        val updatedPost = postRepository.findById(testPost.id!!).orElseThrow()
        assertThat(updatedPost.commentCount).isEqualTo(initialCount + 1)
    }

    @Test
    @DisplayName("댓글 작성 시 게시물이 존재하지 않으면 POST_NOT_FOUND 예외가 발생한다")
    fun createComment_withNonExistentPost_throwsPostNotFoundException() {
        // Given
        val nonExistentPostId = UUID.randomUUID()
        val request =
            CreateCommentRequest(
                postId = nonExistentPostId,
                content = "테스트 댓글",
                parentId = null,
            )

        // When & Then
        assertThatThrownBy {
            commentWriteService.createComment(testUser.id!!, request)
        }
            .isInstanceOf(ApiException::class.java)
            .hasFieldOrPropertyWithValue("status", PostStatus.POST_NOT_FOUND)
    }

    @Test
    @DisplayName("댓글 작성 시 사용자가 존재하지 않으면 ID_NOT_FOUND 예외가 발생한다")
    fun createComment_withNonExistentUser_throwsUserNotFoundException() {
        // Given
        val nonExistentUserId = UUID.randomUUID()
        val request =
            CreateCommentRequest(
                postId = testPost.id!!,
                content = "테스트 댓글",
                parentId = null,
            )

        // When & Then
        assertThatThrownBy {
            commentWriteService.createComment(nonExistentUserId, request)
        }
            .isInstanceOf(ApiException::class.java)
            .hasFieldOrPropertyWithValue("status", UserStatus.ID_NOT_FOUND)
    }

    @Test
    @DisplayName("부모 댓글이 null이면 일반 댓글(depth=0)로 생성된다")
    fun resolveParent_withNullParentId_createsTopLevelComment() {
        // Given
        val request =
            CreateCommentRequest(
                postId = testPost.id!!,
                content = "일반 댓글입니다",
                parentId = null,
            )

        // When
        val response = commentWriteService.createComment(testUser.id!!, request)

        // Then
        val savedComment = commentRepository.findById(response.id).orElseThrow()
        assertThat(savedComment.depth).isEqualTo(0)
        assertThat(savedComment.parent).isNull()
    }

    @Test
    @DisplayName("유효한 부모 댓글이 있으면 대댓글(depth=1)로 생성된다")
    fun resolveParent_withValidParent_createsReply() {
        // Given
        val parentComment =
            commentRepository.save(
                Comment(
                    content = "부모 댓글",
                    post = testPost,
                    author = testUser,
                    parent = null,
                    depth = 0,
                ),
            )

        val request =
            CreateCommentRequest(
                postId = testPost.id!!,
                content = "대댓글입니다",
                parentId = parentComment.id,
            )

        // When
        val response = commentWriteService.createComment(testUser.id!!, request)

        // Then
        val savedComment = commentRepository.findById(response.id).orElseThrow()
        val updatedParentComment = commentRepository.findById(parentComment.id!!).orElseThrow()
        assertThat(savedComment.depth).isEqualTo(1)
        assertThat(savedComment.parent?.id).isEqualTo(parentComment.id)
        assertThat(updatedParentComment.replyCount).isEqualTo(1)
    }

    @Test
    @DisplayName("대댓글 작성 시 게시물 일별 댓글 통계가 1 증가한다")
    fun createReply_incrementsPostDailyCommentCount() {
        // Given
        val parentComment =
            commentRepository.save(
                Comment(
                    content = "부모 댓글",
                    post = testPost,
                    author = testUser,
                    parent = null,
                    depth = 0,
                ),
            )
        val request =
            CreateCommentRequest(
                postId = testPost.id!!,
                content = "대댓글입니다",
                parentId = parentComment.id,
            )

        // When
        commentWriteService.createComment(testUser.id!!, request)
        entityManager.flush()
        entityManager.clear()

        // Then
        val dailyStats = postDailyStatsRepository.findAll().single()
        assertThat(dailyStats.post.id).isEqualTo(testPost.id)
        assertThat(dailyStats.commentCount).isEqualTo(1)
    }

    @Test
    @DisplayName("일반 댓글 작성 시 게시물 작성자에게 POST_COMMENT 알림이 생성된다")
    fun createComment_createsPostCommentNotificationForPostAuthor() {
        val commenter = createUser("댓글 작성자")
        val request =
            CreateCommentRequest(
                postId = testPost.id!!,
                content = "좋은 글이네요",
                parentId = null,
            )

        commentWriteService.createComment(commenter.id!!, request)
        entityManager.flush()
        entityManager.clear()

        val savedNotification = notificationRepository.findAll().single()

        assertThat(savedNotification.type).isEqualTo(NotificationType.POST_COMMENT)
        assertThat(recipientIdsOf(savedNotification.id!!)).containsExactly(testUser.id)
    }

    @Test
    @DisplayName("대댓글 작성 시 게시물 작성자와 부모 댓글 작성자에게 각각 알림이 생성된다")
    fun createReply_createsNotificationsForPostAuthorAndParentAuthor() {
        val parentAuthor = createUser("부모 작성자")
        val replyAuthor = createUser("답글 작성자")
        val parentComment =
            commentRepository.save(
                Comment(
                    content = "부모 댓글",
                    post = testPost,
                    author = parentAuthor,
                    parent = null,
                    depth = 0,
                ),
            )
        val request =
            CreateCommentRequest(
                postId = testPost.id!!,
                content = "대댓글입니다",
                parentId = parentComment.id,
            )

        commentWriteService.createComment(replyAuthor.id!!, request)
        entityManager.flush()
        entityManager.clear()

        val notificationsByType = notificationRepository.findAll().associateBy { it.type }

        assertThat(notificationsByType.keys)
            .containsExactlyInAnyOrder(NotificationType.POST_COMMENT, NotificationType.COMMENT_REPLY)
        assertThat(recipientIdsOf(notificationsByType.getValue(NotificationType.POST_COMMENT).id!!))
            .containsExactly(testUser.id)
        assertThat(recipientIdsOf(notificationsByType.getValue(NotificationType.COMMENT_REPLY).id!!))
            .containsExactly(parentAuthor.id)
    }

    @Test
    @DisplayName("부모 댓글이 존재하지 않으면 COMMENT_NOT_FOUND 예외가 발생한다")
    fun resolveParent_withNonExistentParent_throwsCommentNotFoundException() {
        // Given
        val nonExistentParentId = UUID.randomUUID()
        val request =
            CreateCommentRequest(
                postId = testPost.id!!,
                content = "대댓글",
                parentId = nonExistentParentId,
            )

        // When & Then
        assertThatThrownBy {
            commentWriteService.createComment(testUser.id!!, request)
        }
            .isInstanceOf(ApiException::class.java)
            .hasFieldOrPropertyWithValue("status", CommentStatus.COMMENT_NOT_FOUND)
    }

    @Test
    @DisplayName("부모 댓글이 다른 게시물의 댓글이면 COMMENT_PARENT_MISMATCH 예외가 발생한다")
    fun resolveParent_withParentFromDifferentPost_throwsParentMismatchException() {
        // Given
        val anotherPost =
            postRepository.save(
                Post(
                    title = "다른 게시물",
                    content = "다른 내용",
                    author = testUser,
                    category = testCategory,
                    commentCount = 0,
                ),
            )

        val parentCommentOnAnotherPost =
            commentRepository.save(
                Comment(
                    content = "다른 게시물의 댓글",
                    post = anotherPost,
                    author = testUser,
                    parent = null,
                    depth = 0,
                ),
            )

        val request =
            CreateCommentRequest(
                postId = testPost.id!!,
                content = "대댓글",
                parentId = parentCommentOnAnotherPost.id,
            )

        // When & Then
        assertThatThrownBy {
            commentWriteService.createComment(testUser.id!!, request)
        }
            .isInstanceOf(ApiException::class.java)
            .hasFieldOrPropertyWithValue("status", CommentStatus.COMMENT_PARENT_MISMATCH)
    }

    @Test
    @DisplayName("부모 댓글의 depth가 0이 아니면 COMMENT_MAX_DEPTH_EXCEEDED 예외가 발생한다 (대대댓글 방지)")
    fun resolveParent_withParentDepthNotZero_throwsMaxDepthExceededException() {
        // Given
        val parentComment =
            commentRepository.save(
                Comment(
                    content = "부모 댓글",
                    post = testPost,
                    author = testUser,
                    parent = null,
                    depth = 0,
                ),
            )

        val replyComment =
            commentRepository.save(
                Comment(
                    content = "대댓글",
                    post = testPost,
                    author = testUser,
                    parent = parentComment,
                    depth = 1,
                ),
            )

        val request =
            CreateCommentRequest(
                postId = testPost.id!!,
                content = "대대댓글 시도",
                parentId = replyComment.id,
            )

        // When & Then
        assertThatThrownBy {
            commentWriteService.createComment(testUser.id!!, request)
        }
            .isInstanceOf(ApiException::class.java)
            .hasFieldOrPropertyWithValue("status", CommentStatus.COMMENT_MAX_DEPTH_EXCEEDED)
    }

    @Nested
    @DisplayName("댓글 작성 시 HTML 원문 보존")
    inner class CreateCommentRawHtmlPreservation {
        @Test
        @DisplayName("댓글 content의 HTML 문자열을 제거하지 않고 저장한다")
        fun createComment_preservesRawHtml() {
            // Given
            val request =
                CreateCommentRequest(
                    postId = testPost.id!!,
                    content = """<p>댓글</p><script>alert('xss')</script><div onclick="alert('xss')">내용</div>""",
                    parentId = null,
                )

            // When
            val response = commentWriteService.createComment(testUser.id!!, request)

            // Then
            val savedComment = commentRepository.findById(response.id).orElseThrow()
            assertThat(savedComment.content).isEqualTo(
                """<p>댓글</p><script>alert('xss')</script><div onclick="alert('xss')">내용</div>""",
            )
        }

        @Test
        @DisplayName("댓글 수정 시 content의 HTML 문자열을 제거하지 않고 저장한다")
        fun updateComment_preservesRawHtml() {
            // Given
            val comment =
                commentRepository.save(
                    Comment(
                        content = "원본 댓글",
                        post = testPost,
                        author = testUser,
                    ),
                )
            val request =
                UpdateCommentRequest(
                    content = """<p>수정 댓글</p><iframe src="https://evil.com"></iframe>""",
                )

            // When
            val response = commentWriteService.updateComment(comment.id!!, testUser.id!!, request)

            // Then
            assertThat(response.content).isEqualTo("""<p>수정 댓글</p><iframe src="https://evil.com"></iframe>""")
        }
    }

    @Test
    @DisplayName("여러 댓글이 연속으로 작성될 때 commentCount가 정확히 증가한다")
    fun createComment_multipleComments_incrementsCommentCountCorrectly() {
        // Given
        val initialCount = testPost.commentCount
        val numberOfComments = 5

        // When
        repeat(numberOfComments) { index ->
            val request =
                CreateCommentRequest(
                    postId = testPost.id!!,
                    content = "댓글 $index",
                    parentId = null,
                )
            commentWriteService.createComment(testUser.id!!, request)
        }
        entityManager.flush()
        entityManager.clear()

        // Then
        val updatedPost = postRepository.findById(testPost.id!!).orElseThrow()
        assertThat(updatedPost.commentCount).isEqualTo(initialCount + numberOfComments)
    }

    private fun recipientIdsOf(notificationId: UUID): List<UUID?> =
        notificationRecipientRepository.findAllByNotificationIdOrderByCreatedAtAsc(notificationId).map { it.recipientUser.id }

    private fun createUser(name: String): User {
        val uniqueSuffix = UUID.randomUUID().toString().take(8)
        return userRepository.save(
            User(
                name = "$name-$uniqueSuffix",
                email = "user-$uniqueSuffix@example.com",
                provider = OAuthProvider.GOOGLE,
                identifier = "identifier-$uniqueSuffix",
                role = UserRole.USER,
                profileImageUrl = "https://example.com/$uniqueSuffix.jpg",
            ),
        )
    }
}
