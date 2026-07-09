package com.techtaurant.mainserver.post.application

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.notification.enums.NotificationType
import com.techtaurant.mainserver.notification.infrastructure.out.NotificationRecipientRepository
import com.techtaurant.mainserver.notification.infrastructure.out.NotificationRepository
import com.techtaurant.mainserver.post.dto.CreatePostRequest
import com.techtaurant.mainserver.post.dto.UpdatePostRequest
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.entity.UserFollow
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserFollowRepository
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Transactional
@ActiveProfiles("test")
class PostWriteServiceTest : IntegrationTest() {
    @Autowired
    private lateinit var postWriteService: PostWriteService

    @Autowired
    private lateinit var postRepository: PostRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var userFollowRepository: UserFollowRepository

    @Autowired
    private lateinit var notificationRepository: NotificationRepository

    @Autowired
    private lateinit var notificationRecipientRepository: NotificationRecipientRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    private lateinit var testUser: User

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
    }

    @Test
    @DisplayName("PUBLISHED 게시물 작성 시 모든 팔로워에게 FOLLOWER_POST 알림이 생성된다")
    fun createPost_published_createsFollowerNotifications() {
        val firstFollower = createUser("팔로워A")
        val secondFollower = createUser("팔로워B")
        userFollowRepository.save(UserFollow(follower = firstFollower, following = testUser))
        userFollowRepository.save(UserFollow(follower = secondFollower, following = testUser))

        postWriteService.createPost(
            testUser.id!!,
            CreatePostRequest(
                title = "새 글",
                content = "새 글 본문",
                status = PostStatusEnum.PUBLISHED,
            ),
        )

        val savedNotification = notificationRepository.findAll().single()

        assertThat(savedNotification.type).isEqualTo(NotificationType.FOLLOWER_POST)
        assertThat(recipientIdsOf(savedNotification.id!!)).containsExactlyInAnyOrder(firstFollower.id, secondFollower.id)
    }

    @Test
    @DisplayName("DRAFT 게시물 작성 시 팔로워 알림은 생성되지 않는다")
    fun createPost_draft_doesNotCreateFollowerNotifications() {
        val follower = createUser("초안팔로워")
        userFollowRepository.save(UserFollow(follower = follower, following = testUser))

        postWriteService.createPost(
            testUser.id!!,
            CreatePostRequest(
                status = PostStatusEnum.DRAFT,
            ),
        )

        assertThat(notificationRepository.findAll()).isEmpty()
    }

    @Test
    @DisplayName("생성 요청에 createdAt이 있으면 게시물 생성일시로 저장된다")
    fun createPost_withCreatedAt_savesRequestedCreatedAt() {
        val requestedCreatedAt = Instant.parse("2026-04-25T10:15:30Z")
        val expectedCreatedAt = requestedCreatedAt

        val response =
            postWriteService.createPost(
                testUser.id!!,
                CreatePostRequest(
                    title = "예약 생성일시 글",
                    content = "생성일시를 직접 입력한 본문",
                    status = PostStatusEnum.PUBLISHED,
                    createdAt = requestedCreatedAt,
                ),
            )

        entityManager.flush()
        entityManager.clear()
        val savedPost = postRepository.findById(response.id).orElseThrow()

        assertThat(response.createdAt).isEqualTo(expectedCreatedAt)
        assertThat(savedPost.createdAt.toEpochMilli()).isEqualTo(expectedCreatedAt.toEpochMilli())
    }

    @Test
    @DisplayName("생성 요청에 createdAt이 없으면 현재 시점으로 게시물이 작성된다")
    fun createPost_withoutCreatedAt_savesCurrentCreatedAt() {
        val beforeCreate = Instant.now()

        val response =
            postWriteService.createPost(
                testUser.id!!,
                CreatePostRequest(
                    title = "현재 생성일시 글",
                    content = "생성일시를 입력하지 않은 본문",
                    status = PostStatusEnum.PUBLISHED,
                ),
            )

        val afterCreate = Instant.now()
        entityManager.flush()
        entityManager.clear()
        val savedPost = postRepository.findById(response.id).orElseThrow()

        assertThat(response.createdAt.toEpochMilli()).isBetween(beforeCreate.toEpochMilli(), afterCreate.toEpochMilli())
        assertThat(savedPost.createdAt.toEpochMilli()).isBetween(beforeCreate.toEpochMilli(), afterCreate.toEpochMilli())
    }

    @Nested
    @DisplayName("게시물 작성 시 HTML 원문 보존")
    inner class CreatePostRawHtmlPreservation {
        @Test
        @DisplayName("title과 content의 HTML 문자열을 제거하지 않고 저장한다")
        fun createPost_preservesRawHtml() {
            // Given
            val request =
                CreatePostRequest(
                    title = "<h1>제목</h1><script>alert('xss')</script>",
                    content = """<p>본문</p><script>alert('xss')</script><a href="javascript:alert('xss')">클릭</a>""",
                    status = PostStatusEnum.PUBLISHED,
                )

            // When
            val response = postWriteService.createPost(testUser.id!!, request)

            // Then
            assertThat(response.title).isEqualTo("<h1>제목</h1><script>alert('xss')</script>")
            assertThat(response.content).isEqualTo("""<p>본문</p><script>alert('xss')</script><a href="javascript:alert('xss')">클릭</a>""")
        }

        @Test
        @DisplayName("수정 시 title과 content의 HTML 문자열을 제거하지 않고 저장한다")
        fun updatePost_preservesRawHtml() {
            // Given
            val created =
                postWriteService.createPost(
                    testUser.id!!,
                    CreatePostRequest(
                        title = "원본 제목",
                        content = "원본 본문",
                        status = PostStatusEnum.PUBLISHED,
                    ),
                )
            val updateRequest =
                UpdatePostRequest(
                    title = "<b>수정된 제목</b><script>hack()</script>",
                    content = "<p>수정된 본문</p><style>body{display:none}</style>",
                )

            // When
            val response = postWriteService.updatePost(created.id, updateRequest, testUser.id!!)

            // Then
            assertThat(response.title).isEqualTo("<b>수정된 제목</b><script>hack()</script>")
            assertThat(response.content).isEqualTo("<p>수정된 본문</p><style>body{display:none}</style>")
        }
    }

    private fun recipientIdsOf(notificationId: UUID): List<UUID?> =
        notificationRecipientRepository.findAllByNotificationIdOrderByCreatedAtAsc(notificationId).map { it.recipientUser.id }

    private fun createUser(name: String): User {
        val uniqueSuffix = UUID.randomUUID().toString().take(8)
        return userRepository.save(
            User(
                name = "$name-$uniqueSuffix",
                email = "$uniqueSuffix@example.com",
                provider = OAuthProvider.GOOGLE,
                identifier = "test-id-$uniqueSuffix",
                role = UserRole.USER,
                profileImageUrl = "https://example.com/$uniqueSuffix.jpg",
            ),
        )
    }
}
