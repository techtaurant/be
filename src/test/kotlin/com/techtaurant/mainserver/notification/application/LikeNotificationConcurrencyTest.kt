package com.techtaurant.mainserver.notification.application

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.comment.application.CommentLikeLogService
import com.techtaurant.mainserver.comment.entity.Comment
import com.techtaurant.mainserver.comment.infrastructure.out.CommentRepository
import com.techtaurant.mainserver.common.enums.LikeStatus
import com.techtaurant.mainserver.notification.enums.NotificationTargetType
import com.techtaurant.mainserver.notification.enums.NotificationType
import com.techtaurant.mainserver.notification.infrastructure.out.NotificationRepository
import com.techtaurant.mainserver.post.application.PostLikeLogService
import com.techtaurant.mainserver.post.entity.Category
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.infrastructure.out.CategoryRepository
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 좋아요 알림 동시성 통합 테스트
 *
 * 같은 사용자가 같은 대상에 대해 DISLIKE → LIKE 전이를 동시에 요청(따닥)해도
 * PESSIMISTIC_WRITE 행 잠금으로 전이가 직렬화되어 좋아요 알림이 한 번만 생성되는지 검증합니다.
 */
@DisplayName("좋아요 알림 동시성 통합 테스트")
class LikeNotificationConcurrencyTest : IntegrationTest() {
    @Autowired
    private lateinit var postLikeLogService: PostLikeLogService

    @Autowired
    private lateinit var commentLikeLogService: CommentLikeLogService

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var categoryRepository: CategoryRepository

    @Autowired
    private lateinit var postRepository: PostRepository

    @Autowired
    private lateinit var commentRepository: CommentRepository

    @Autowired
    private lateinit var notificationRepository: NotificationRepository

    private lateinit var author: User
    private lateinit var liker: User
    private lateinit var testPost: Post
    private lateinit var testComment: Comment

    @BeforeEach
    fun setUpTestData() {
        author = saveUser("작성자")
        liker = saveUser("좋아요사용자")

        val category =
            categoryRepository.save(
                Category(
                    user = author,
                    name = "테스트카테고리",
                    path = "테스트카테고리",
                    depth = 1,
                ),
            )

        testPost =
            postRepository.save(
                Post(
                    title = "테스트 게시물",
                    content = "테스트 게시물 내용입니다",
                    author = author,
                    category = category,
                ),
            )

        testComment =
            commentRepository.save(
                Comment(
                    content = "테스트 댓글입니다",
                    post = testPost,
                    author = author,
                    parent = null,
                    depth = 0,
                ),
            )
    }

    @Test
    @DisplayName("동시 게시물 좋아요 전이는 POST_LIKE 알림을 한 번만 생성한다")
    fun concurrentPostLikeTransition_createsSinglePostLikeNotification() {
        // Given - 싫어요로 좋아요 로그가 이미 존재
        postLikeLogService.recordLike(testPost.id!!, liker.id!!, LikeStatus.DISLIKE)

        // When - 동시에 좋아요 전이(따닥)
        runConcurrently {
            postLikeLogService.recordLike(testPost.id!!, liker.id!!, LikeStatus.LIKE)
        }

        // Then - 작성자에게 POST_LIKE 알림 1건만 생성
        val notifications =
            notificationRepository.findAllByTypeAndActorAndTarget(
                NotificationType.POST_LIKE,
                liker.id!!,
                NotificationTargetType.POST,
                testPost.id!!,
            )
        assertThat(notifications).hasSize(1)
    }

    @Test
    @DisplayName("동시 댓글 좋아요 전이는 COMMENT_LIKE 알림을 한 번만 생성한다")
    fun concurrentCommentLikeTransition_createsSingleCommentLikeNotification() {
        // Given - 싫어요로 좋아요 로그가 이미 존재
        commentLikeLogService.recordLike(testComment.id!!, liker.id!!, LikeStatus.DISLIKE)

        // When - 동시에 좋아요 전이(따닥)
        runConcurrently {
            commentLikeLogService.recordLike(testComment.id!!, liker.id!!, LikeStatus.LIKE)
        }

        // Then - 작성자에게 COMMENT_LIKE 알림 1건만 생성
        val notifications =
            notificationRepository.findAllByTypeAndActorAndTarget(
                NotificationType.COMMENT_LIKE,
                liker.id!!,
                NotificationTargetType.COMMENT,
                testComment.id!!,
            )
        assertThat(notifications).hasSize(1)
    }

    private fun saveUser(name: String): User =
        userRepository.saveAndFlush(
            User(
                name = name,
                email = "user-${UUID.randomUUID()}@example.com",
                provider = OAuthProvider.GOOGLE,
                identifier = "user-id-${UUID.randomUUID()}",
                role = UserRole.USER,
                profileImageUrl = "https://example.com/profile.jpg",
            ),
        )

    private fun runConcurrently(operation: () -> Unit) {
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val failures = ConcurrentLinkedQueue<Throwable>()

        repeat(2) {
            executor.execute {
                try {
                    ready.countDown()
                    start.await()
                    operation()
                } catch (throwable: Throwable) {
                    failures.add(throwable)
                } finally {
                    done.countDown()
                }
            }
        }

        try {
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()
            assertThat(failures).isEmpty()
        } finally {
            executor.shutdownNow()
        }
    }
}
