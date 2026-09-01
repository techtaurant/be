package com.techtaurant.mainserver.user.infrastructure.`in`

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.entity.UserBan
import com.techtaurant.mainserver.user.entity.UserFollow
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserBanRepository
import com.techtaurant.mainserver.user.infrastructure.out.UserFollowRepository
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.util.UUID

@DisplayName("UserController ban 통합 테스트")
class UserControllerBanIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var userBanRepository: UserBanRepository

    @Autowired
    private lateinit var userFollowRepository: UserFollowRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    private lateinit var testUser: User
    private lateinit var targetUser: User
    private lateinit var accessToken: String

    @BeforeEach
    fun setUpTestData() {
        userBanRepository.deleteAllInBatch()
        userFollowRepository.deleteAllInBatch()

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
        targetUser =
            userRepository.save(
                User(
                    name = "차단대상",
                    email = "target@example.com",
                    provider = OAuthProvider.GOOGLE,
                    identifier = "target-id-${UUID.randomUUID()}",
                    role = UserRole.USER,
                    profileImageUrl = "https://example.com/target.jpg",
                ),
            )
        accessToken = jwtTokenProvider.createAccessToken(testUser.id!!, testUser.role)
    }

    @Test
    @DisplayName("사용자 차단이 성공한다")
    fun ban_success() {
        // When & Then
        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .`when`()
            .post("/api/users/${targetUser.id}/ban")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .body("data.userId", equalTo(targetUser.id.toString()))
            .body("data.name", equalTo(targetUser.name))
    }

    @Test
    @DisplayName("차단 목록 조회가 성공한다")
    fun getBannedUsers_success() {
        // Given
        userBanRepository.save(UserBan(user = testUser, bannedUser = targetUser))

        // When & Then
        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .`when`()
            .get("/api/users/me/bans")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data", hasSize<Any>(1))
            .body("data[0].userId", equalTo(targetUser.id.toString()))
    }

    @Test
    @DisplayName("차단 해제가 성공한다")
    fun unban_success() {
        // Given
        userBanRepository.save(UserBan(user = testUser, bannedUser = targetUser))

        // When & Then
        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .`when`()
            .delete("/api/users/${targetUser.id}/ban")
            .then()
            .statusCode(HttpStatus.NO_CONTENT.value())
    }

    @Test
    @DisplayName("차단 해제 후 목록이 비어있다")
    fun getBannedUsers_afterUnban_returnsEmpty() {
        // Given
        userBanRepository.save(UserBan(user = testUser, bannedUser = targetUser))

        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .`when`()
            .delete("/api/users/${targetUser.id}/ban")

        // When & Then
        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .`when`()
            .get("/api/users/me/bans")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data", hasSize<Any>(0))
    }

    @Test
    @DisplayName("사용자 차단 시 양방향 팔로우 관계가 자동으로 해제된다")
    fun ban_removesMutualFollows() {
        userFollowRepository.save(UserFollow(follower = testUser, following = targetUser))
        userFollowRepository.save(UserFollow(follower = targetUser, following = testUser))

        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .`when`()
            .post("/api/users/${targetUser.id}/ban")
            .then()
            .statusCode(HttpStatus.CREATED.value())

        kotlin.test.assertNull(userFollowRepository.findByFollowerIdAndFollowingId(testUser.id!!, targetUser.id!!))
        kotlin.test.assertNull(userFollowRepository.findByFollowerIdAndFollowingId(targetUser.id!!, testUser.id!!))
    }
}
