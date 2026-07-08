package com.techtaurant.mainserver.post.application

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.common.enums.LikeStatus
import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.common.util.DateUtils
import com.techtaurant.mainserver.notification.enums.NotificationTargetType
import com.techtaurant.mainserver.notification.enums.NotificationType
import com.techtaurant.mainserver.notification.infrastructure.out.NotificationRepository
import com.techtaurant.mainserver.post.entity.Category
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.enums.PostStatus
import com.techtaurant.mainserver.post.infrastructure.out.CategoryRepository
import com.techtaurant.mainserver.post.infrastructure.out.PostDailyStatsRepository
import com.techtaurant.mainserver.post.infrastructure.out.PostLikeLogRepository
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
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * PostLikeLogService 통합 테스트
 *
 * TestContainers를 활용하여 실제 PostgreSQL 데이터베이스 환경에서
 * 게시물 좋아요/싫어요 로직을 검증합니다.
 */
@DisplayName("PostLikeLogService 통합 테스트")
@Transactional
class PostLikeLogServiceTest : IntegrationTest() {
    @Autowired
    private lateinit var postLikeLogService: PostLikeLogService

    @Autowired
    private lateinit var postRepository: PostRepository

    @Autowired
    private lateinit var postLikeLogRepository: PostLikeLogRepository

    @Autowired
    private lateinit var postDailyStatsRepository: PostDailyStatsRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var categoryRepository: CategoryRepository

    @Autowired
    private lateinit var postDailyStatsService: PostDailyStatsService

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var notificationRepository: NotificationRepository

    private lateinit var testUser: User
    private lateinit var testPost: Post

    @BeforeEach
    fun setUpTestData() {
        // Given - 테스트 사용자 생성
        testUser =
            userRepository.save(
                User(
                    name = "테스트사용자",
                    email = "test@example.com",
                    provider = OAuthProvider.GOOGLE,
                    identifier = "test-id-${UUID.randomUUID()}",
                    role = UserRole.USER,
                    profileImageUrl = "https://example.com/profile.jpg",
                ),
            )

        // Given - 테스트 카테고리 생성
        val testCategory =
            categoryRepository.save(
                Category(
                    user = testUser,
                    name = "테스트카테고리",
                    path = "테스트카테고리",
                    depth = 1,
                ),
            )

        // Given - 테스트 게시물 생성
        testPost =
            postRepository.save(
                Post(
                    title = "테스트 게시물",
                    content = "테스트 게시물 내용입니다",
                    author = testUser,
                    category = testCategory,
                ),
            )
    }

    @Test
    @DisplayName("중립 상태에서 좋아요를 기록하면 likeCount가 1 증가한다")
    fun recordLike_fromNeutralToLike_shouldIncrementLikeCount() {
        // Given - 초기 likeCount = 0

        // When - 좋아요 기록
        postLikeLogService.recordLike(testPost.id!!, testUser.id!!, LikeStatus.LIKE)

        // Then - 변경사항 DB 반영 및 1차 캐시 갱신
        entityManager.flush()
        entityManager.refresh(testPost)

        // Then - likeCount가 1 증가
        assertThat(testPost.likeCount).isEqualTo(1)

        // Then - 좋아요 로그 생성 확인
        val log = postLikeLogRepository.findByPostIdAndUserId(testPost.id!!, testUser.id!!)
        assertThat(log).isNotNull
        assertThat(log?.isLiked).isTrue()
    }

    @Test
    @DisplayName("중립 상태에서 싫어요를 기록하면 likeCount가 1 감소한다")
    fun recordLike_fromNeutralToDislike_shouldDecrementLikeCount() {
        // Given - 초기 likeCount = 0

        // When - 싫어요 기록
        postLikeLogService.recordLike(testPost.id!!, testUser.id!!, LikeStatus.DISLIKE)

        // Then - 원자적 UPDATE 쿼리 이후 영속성 컨텍스트 초기화 및 DB 재조회
        entityManager.flush()
        entityManager.clear()
        val updatedPost = postRepository.findById(testPost.id!!).get()

        // Then - likeCount가 1 감소
        assertThat(updatedPost.likeCount).isEqualTo(-1)

        // Then - 싫어요 로그 생성 확인
        val log = postLikeLogRepository.findByPostIdAndUserId(testPost.id!!, testUser.id!!)
        assertThat(log).isNotNull
        assertThat(log?.isLiked).isFalse()
    }

    @Test
    @DisplayName("좋아요 상태에서 싫어요로 변경하면 likeCount가 2 감소한다")
    fun recordLike_fromLikeToDislike_shouldDecrementLikeCountByTwo() {
        // Given - 이미 좋아요한 상태 (likeCount = 1)
        postLikeLogService.recordLike(testPost.id!!, testUser.id!!, LikeStatus.LIKE)
        entityManager.flush()
        entityManager.clear()
        val initialLikeCount = postRepository.findById(testPost.id!!).get().likeCount

        // When - 싫어요로 변경
        postLikeLogService.recordLike(testPost.id!!, testUser.id!!, LikeStatus.DISLIKE)

        // Then - 원자적 UPDATE 쿼리 이후 영속성 컨텍스트 초기화 및 DB 재조회
        entityManager.flush()
        entityManager.clear()
        val updatedPost = postRepository.findById(testPost.id!!).get()

        // Then - likeCount가 2 감소 (좋아요 취소 -1 + 싫어요 적용 -1)
        assertThat(updatedPost.likeCount).isEqualTo(initialLikeCount - 2)

        // Then - 싫어요 로그로 업데이트 확인
        val log = postLikeLogRepository.findByPostIdAndUserId(testPost.id!!, testUser.id!!)
        assertThat(log).isNotNull
        assertThat(log?.isLiked).isFalse()
    }

    @Test
    @DisplayName("싫어요 상태에서 좋아요로 변경하면 likeCount가 2 증가한다")
    fun recordLike_fromDislikeToLike_shouldIncrementLikeCountByTwo() {
        // Given - 이미 싫어요한 상태 (likeCount = -1)
        postLikeLogService.recordLike(testPost.id!!, testUser.id!!, LikeStatus.DISLIKE)
        entityManager.flush()
        entityManager.clear()
        val initialLikeCount = postRepository.findById(testPost.id!!).get().likeCount

        // When - 좋아요로 변경
        postLikeLogService.recordLike(testPost.id!!, testUser.id!!, LikeStatus.LIKE)

        // Then - 원자적 UPDATE 쿼리 이후 영속성 컨텍스트 초기화 및 DB 재조회
        entityManager.flush()
        entityManager.clear()
        val updatedPost = postRepository.findById(testPost.id!!).get()

        // Then - likeCount가 2 증가 (싫어요 취소 +1 + 좋아요 적용 +1)
        assertThat(updatedPost.likeCount).isEqualTo(initialLikeCount + 2)

        // Then - 좋아요 로그로 업데이트 확인
        val log = postLikeLogRepository.findByPostIdAndUserId(testPost.id!!, testUser.id!!)
        assertThat(log).isNotNull
        assertThat(log?.isLiked).isTrue()
    }

    @Test
    @DisplayName("이미 좋아요한 상태에서 다시 좋아요를 기록하면 likeCount 변화가 없다")
    fun recordLike_duplicateLike_shouldNotChangeLikeCount() {
        // Given - 이미 좋아요한 상태
        postLikeLogService.recordLike(testPost.id!!, testUser.id!!, LikeStatus.LIKE)
        val initialLikeCount = postRepository.findById(testPost.id!!).get().likeCount

        // When - 다시 좋아요 기록
        postLikeLogService.recordLike(testPost.id!!, testUser.id!!, LikeStatus.LIKE)

        // Then - likeCount 변화 없음
        val updatedPost = postRepository.findById(testPost.id!!).get()
        assertThat(updatedPost.likeCount).isEqualTo(initialLikeCount)

        // Then - 로그는 여전히 좋아요 상태
        val log = postLikeLogRepository.findByPostIdAndUserId(testPost.id!!, testUser.id!!)
        assertThat(log).isNotNull
        assertThat(log?.isLiked).isTrue()
    }

    @Test
    @DisplayName("이미 싫어요한 상태에서 다시 싫어요를 기록하면 likeCount 변화가 없다")
    fun recordLike_duplicateDislike_shouldNotChangeLikeCount() {
        // Given - 이미 싫어요한 상태
        postLikeLogService.recordLike(testPost.id!!, testUser.id!!, LikeStatus.DISLIKE)
        val initialLikeCount = postRepository.findById(testPost.id!!).get().likeCount

        // When - 다시 싫어요 기록
        postLikeLogService.recordLike(testPost.id!!, testUser.id!!, LikeStatus.DISLIKE)

        // Then - likeCount 변화 없음
        val updatedPost = postRepository.findById(testPost.id!!).get()
        assertThat(updatedPost.likeCount).isEqualTo(initialLikeCount)

        // Then - 로그는 여전히 싫어요 상태
        val log = postLikeLogRepository.findByPostIdAndUserId(testPost.id!!, testUser.id!!)
        assertThat(log).isNotNull
        assertThat(log?.isLiked).isFalse()
    }

    @Test
    @DisplayName("존재하지 않는 게시물에 좋아요를 기록하면 예외가 발생한다")
    fun recordLike_withNonExistentPost_shouldThrowException() {
        // Given - 존재하지 않는 게시물 ID
        val nonExistentPostId = UUID.randomUUID()

        // When & Then - ApiException 발생
        assertThatThrownBy {
            postLikeLogService.recordLike(nonExistentPostId, testUser.id!!, LikeStatus.LIKE)
        }
            .isInstanceOf(ApiException::class.java)
            .extracting { (it as ApiException).status }
            .isEqualTo(PostStatus.POST_NOT_FOUND)
    }

    @Test
    @DisplayName("존재하지 않는 사용자가 좋아요를 기록하면 예외가 발생한다")
    fun recordLike_withNonExistentUser_shouldThrowException() {
        // Given - 존재하지 않는 사용자 ID
        val nonExistentUserId = UUID.randomUUID()

        // When & Then - ApiException 발생
        assertThatThrownBy {
            postLikeLogService.recordLike(testPost.id!!, nonExistentUserId, LikeStatus.LIKE)
        }
            .isInstanceOf(ApiException::class.java)
            .extracting { (it as ApiException).status }
            .isEqualTo(UserStatus.ID_NOT_FOUND)
    }

    @Test
    @DisplayName("여러 사용자가 동일 게시물에 좋아요를 기록하면 각각 독립적으로 처리된다")
    fun recordLike_multipleUsers_shouldHandleIndependently() {
        // Given - 추가 사용자 생성
        val anotherUser =
            userRepository.save(
                User(
                    name = "다른사용자",
                    email = "another@example.com",
                    provider = OAuthProvider.GOOGLE,
                    identifier = "another-id-${UUID.randomUUID()}",
                    role = UserRole.USER,
                    profileImageUrl = "https://example.com/another.jpg",
                ),
            )

        // When - 첫 번째 사용자가 좋아요
        postLikeLogService.recordLike(testPost.id!!, testUser.id!!, LikeStatus.LIKE)

        // When - 두 번째 사용자가 싫어요
        postLikeLogService.recordLike(testPost.id!!, anotherUser.id!!, LikeStatus.DISLIKE)

        // Then - likeCount는 0 (좋아요 +1, 싫어요 -1)
        val updatedPost = postRepository.findById(testPost.id!!).get()
        assertThat(updatedPost.likeCount).isEqualTo(0)

        // Then - 각 사용자의 로그가 독립적으로 존재
        val log1 = postLikeLogRepository.findByPostIdAndUserId(testPost.id!!, testUser.id!!)
        assertThat(log1?.isLiked).isTrue()

        val log2 = postLikeLogRepository.findByPostIdAndUserId(testPost.id!!, anotherUser.id!!)
        assertThat(log2?.isLiked).isFalse()
    }

    @Test
    @DisplayName("좋아요 취소 시 일별 통계는 기존 로그 createdAt 기준 날짜를 사용한다")
    fun recordLike_cancelShouldUseExistingLogCreatedAtForDailyStats() {
        // Given - 좋아요 로그 생성
        postLikeLogService.recordLike(testPost.id!!, testUser.id!!, LikeStatus.LIKE)
        entityManager.flush()
        entityManager.clear()

        val savedLog = postLikeLogRepository.findByPostIdAndUserId(testPost.id!!, testUser.id!!)
        val targetCreatedAt = Timestamp.valueOf(LocalDateTime.of(2026, 3, 1, 12, 0, 0))
        updateLogCreatedAt(savedLog!!.id!!, targetCreatedAt)
        entityManager.clear()

        // When - 중립 상태로 되돌리기
        postLikeLogService.recordLike(testPost.id!!, testUser.id!!, LikeStatus.NONE)
        entityManager.flush()
        entityManager.clear()

        // Then - 취소 통계가 기존 로그 생성일 버킷에 반영됨
        val targetStatDate = DateUtils.toUtcDate(targetCreatedAt.toInstant())
        val targetDailyStats = findDailyStats(targetStatDate)
        assertThat(targetDailyStats).isNotNull
        assertThat(targetDailyStats?.likeCount).isEqualTo(-1)
    }

    private fun updateLogCreatedAt(
        logId: UUID,
        createdAt: Timestamp,
    ) {
        entityManager.createNativeQuery("UPDATE post_like_log SET created_at = :createdAt, updated_at = :createdAt WHERE id = :logId")
            .setParameter("createdAt", createdAt)
            .setParameter("logId", logId)
            .executeUpdate()
        entityManager.flush()
    }

    private fun findDailyStats(statDate: LocalDate) =
        postDailyStatsRepository.findAll()
            .find { it.post.id == testPost.id && it.statDate.toString() == statDate.toString() }

    @Test
    @DisplayName("다른 사용자가 좋아요하면 게시물 작성자에게 POST_LIKE 알림이 생성된다")
    fun recordLike_byOtherUser_createsPostLikeNotification() {
        // Given - 작성자가 아닌 좋아요 사용자
        val liker = createLiker()

        // When - 좋아요 기록
        postLikeLogService.recordLike(testPost.id!!, liker.id!!, LikeStatus.LIKE)
        entityManager.flush()

        // Then - 작성자(testUser)에게 POST_LIKE 알림 1건 생성
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
    @DisplayName("본인 게시물에 좋아요하면 알림이 생성되지 않는다")
    fun recordLike_bySelf_doesNotCreateNotification() {
        // When - 작성자 본인이 좋아요
        postLikeLogService.recordLike(testPost.id!!, testUser.id!!, LikeStatus.LIKE)
        entityManager.flush()

        // Then - 알림 없음
        val notifications =
            notificationRepository.findAllByTypeAndActorAndTarget(
                NotificationType.POST_LIKE,
                testUser.id!!,
                NotificationTargetType.POST,
                testPost.id!!,
            )
        assertThat(notifications).isEmpty()
    }

    @Test
    @DisplayName("좋아요를 취소하면 생성됐던 POST_LIKE 알림이 삭제된다")
    fun recordLike_thenCancel_removesPostLikeNotification() {
        // Given - 다른 사용자가 좋아요하여 알림 생성됨
        val liker = createLiker()
        postLikeLogService.recordLike(testPost.id!!, liker.id!!, LikeStatus.LIKE)
        entityManager.flush()

        // When - 좋아요 취소
        postLikeLogService.recordLike(testPost.id!!, liker.id!!, LikeStatus.NONE)
        entityManager.flush()

        // Then - 알림 삭제됨
        val notifications =
            notificationRepository.findAllByTypeAndActorAndTarget(
                NotificationType.POST_LIKE,
                liker.id!!,
                NotificationTargetType.POST,
                testPost.id!!,
            )
        assertThat(notifications).isEmpty()
    }

    private fun createLiker(): User =
        userRepository.save(
            User(
                name = "좋아요사용자",
                email = "liker-${UUID.randomUUID()}@example.com",
                provider = OAuthProvider.GOOGLE,
                identifier = "liker-id-${UUID.randomUUID()}",
                role = UserRole.USER,
                profileImageUrl = "https://example.com/liker.jpg",
            ),
        )
}
