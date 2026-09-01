package com.techtaurant.mainserver.user.infrastructure.`in`

import com.techtaurant.mainserver.attachment.infrastructure.out.AttachmentRepository
import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.dto.UpdateUserRequest
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.enums.UserStatus
import com.techtaurant.mainserver.user.infrastructure.out.UserBanRepository
import com.techtaurant.mainserver.user.infrastructure.out.UserFollowRepository
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.util.UUID

@DisplayName("UserController profile 통합 테스트")
class UserControllerProfileIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var userBanRepository: UserBanRepository

    @Autowired
    private lateinit var userFollowRepository: UserFollowRepository

    @Autowired
    private lateinit var attachmentRepository: AttachmentRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    private lateinit var testUser: User
    private lateinit var accessToken: String

    @BeforeEach
    fun setUpTestData() {
        userBanRepository.deleteAllInBatch()
        userFollowRepository.deleteAllInBatch()
        userRepository.deleteAllInBatch()
        attachmentRepository.deleteAllInBatch()

        testUser =
            userRepository.save(
                User(
                    name = "테스트사용자",
                    email = "test-${UUID.randomUUID()}@example.com",
                    provider = OAuthProvider.GOOGLE,
                    identifier = "test-id-${UUID.randomUUID()}",
                    role = UserRole.USER,
                    profileImageUrl = "https://example.com/default-profile.jpg",
                ),
            )
        accessToken = jwtTokenProvider.createAccessToken(testUser.id!!, testUser.role)
    }

    @Test
    @DisplayName("내 이름 수정이 성공한다")
    fun updateMe_name_success() {
        // given

        // when
        given()
            .contentType("application/json")
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .body(UpdateUserRequest(name = "새이름"))
            .`when`()
            .patch("/api/users/me")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.name", equalTo("새이름"))
            .body("data.profileImageUrl", equalTo("https://example.com/default-profile.jpg"))

        // then
        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .`when`()
            .get("/api/users/me")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.name", equalTo("새이름"))
    }

    @Test
    @DisplayName("중복 닉네임으로 수정하면 409와 중복 닉네임 에러를 반환한다")
    fun updateMe_duplicateName_returnsConflictResponse() {
        // given
        userRepository.save(
            User(
                name = "중복닉네임",
                email = "duplicate-${UUID.randomUUID()}@example.com",
                provider = OAuthProvider.GOOGLE,
                identifier = "duplicate-id-${UUID.randomUUID()}",
                role = UserRole.USER,
                profileImageUrl = "https://example.com/duplicate-profile.jpg",
            ),
        )

        // when & then
        given()
            .contentType("application/json")
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .body(UpdateUserRequest(name = "중복닉네임"))
            .`when`()
            .patch("/api/users/me")
            .then()
            .statusCode(HttpStatus.CONFLICT.value())
            .body("status", equalTo(UserStatus.USER_NAME_ALREADY_EXISTS.getCustomStatusCode()))
            .body("message", equalTo(UserStatus.USER_NAME_ALREADY_EXISTS.getDescription()))
    }
}
