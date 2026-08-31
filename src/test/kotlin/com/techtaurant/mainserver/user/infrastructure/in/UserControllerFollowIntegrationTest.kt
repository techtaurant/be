package com.techtaurant.mainserver.user.infrastructure.`in`

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.notification.enums.NotificationType
import com.techtaurant.mainserver.notification.infrastructure.out.NotificationRecipientRepository
import com.techtaurant.mainserver.notification.infrastructure.out.NotificationRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.entity.UserFollow
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserFollowRepository
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.util.UUID

@DisplayName("UserController follow 통합 테스트")
class UserControllerFollowIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var userFollowRepository: UserFollowRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    private lateinit var notificationRepository: NotificationRepository

    @Autowired
    private lateinit var notificationRecipientRepository: NotificationRecipientRepository

    private lateinit var testUser: User
    private lateinit var firstTargetUser: User
    private lateinit var secondTargetUser: User
    private lateinit var followerUser: User
    private lateinit var accessToken: String

    @BeforeEach
    fun setUpTestData() {
        userFollowRepository.deleteAllInBatch()

        testUser = createUser(name = "테스트사용자", emailPrefix = "test")
        firstTargetUser = createUser(name = "첫번째대상", emailPrefix = "target1")
        secondTargetUser = createUser(name = "두번째대상", emailPrefix = "target2")
        followerUser = createUser(name = "팔로워사용자", emailPrefix = "follower")
        accessToken = jwtTokenProvider.createAccessToken(testUser.id!!, testUser.role)
    }

    @Test
    @DisplayName("사용자 팔로우가 성공한다")
    fun follow_success() {
        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .`when`()
            .post("/api/users/${firstTargetUser.id}/follow")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .body("data.userId", equalTo(firstTargetUser.id.toString()))
            .body("data.name", equalTo(firstTargetUser.name))
    }

    @Test
    @DisplayName("사용자 팔로우 시 피팔로우 사용자에게 FOLLOW 알림이 생성된다")
    fun follow_createsNotificationForTargetUser() {
        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .`when`()
            .post("/api/users/${firstTargetUser.id}/follow")
            .then()
            .statusCode(HttpStatus.CREATED.value())

        val savedNotification = notificationRepository.findAll().single()
        val recipientIds =
            notificationRecipientRepository
                .findAllByNotificationIdOrderByCreatedAtAsc(savedNotification.id!!)
                .map { it.recipientUser.id }

        assertThat(savedNotification.type).isEqualTo(NotificationType.FOLLOW)
        assertThat(recipientIds).containsExactly(firstTargetUser.id)
    }

    @Test
    @DisplayName("팔로워 수와 팔로우 수 조회가 성공한다")
    fun getFollowCounts_success() {
        userFollowRepository.save(UserFollow(follower = testUser, following = firstTargetUser))
        userFollowRepository.save(UserFollow(follower = firstTargetUser, following = secondTargetUser))
        userFollowRepository.save(UserFollow(follower = followerUser, following = firstTargetUser))

        given()
            .`when`()
            .get("/open-api/users/${firstTargetUser.id}/follow-counts")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.followerCount", equalTo(2))
            .body("data.followingCount", equalTo(1))
    }

    @Test
    @DisplayName("팔로잉 목록 조회가 성공한다")
    fun getFollowings_success() {
        userFollowRepository.save(UserFollow(follower = testUser, following = firstTargetUser))
        userFollowRepository.save(UserFollow(follower = testUser, following = secondTargetUser))

        given()
            .`when`()
            .get("/open-api/users/${testUser.id}/followings")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data", hasSize<Any>(2))
            .body("data.userId", hasItems(firstTargetUser.id.toString(), secondTargetUser.id.toString()))
    }

    @Test
    @DisplayName("팔로워 목록 조회가 성공한다")
    fun getFollowers_success() {
        userFollowRepository.save(UserFollow(follower = testUser, following = firstTargetUser))
        userFollowRepository.save(UserFollow(follower = followerUser, following = firstTargetUser))

        given()
            .`when`()
            .get("/open-api/users/${firstTargetUser.id}/followers")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data", hasSize<Any>(2))
            .body("data.userId", hasItems(testUser.id.toString(), followerUser.id.toString()))
    }

    @Test
    @DisplayName("팔로우 취소가 성공한다")
    fun unfollow_success() {
        userFollowRepository.save(UserFollow(follower = testUser, following = firstTargetUser))

        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .`when`()
            .delete("/api/users/${firstTargetUser.id}/follow")
            .then()
            .statusCode(HttpStatus.NO_CONTENT.value())

        given()
            .`when`()
            .get("/open-api/users/${testUser.id}/follow-counts")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.followerCount", equalTo(0))
            .body("data.followingCount", equalTo(0))
    }

    private fun createUser(
        name: String,
        emailPrefix: String,
    ): User {
        return userRepository.save(
            User(
                name = name,
                email = "$emailPrefix-${UUID.randomUUID()}@example.com",
                provider = OAuthProvider.GOOGLE,
                identifier = "$emailPrefix-id-${UUID.randomUUID()}",
                role = UserRole.USER,
                profileImageUrl = "https://example.com/$emailPrefix.jpg",
            ),
        )
    }
}
