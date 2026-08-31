package com.techtaurant.mainserver.user.infrastructure.`in`

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.util.UUID
import kotlin.test.assertEquals

@DisplayName("AdminUserRoleController 통합 테스트")
class AdminUserRoleControllerIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    private lateinit var adminUser: User
    private lateinit var targetUser: User
    private lateinit var normalUser: User
    private lateinit var adminAccessToken: String
    private lateinit var userAccessToken: String

    @BeforeEach
    fun setUpTestData() {
        userRepository.deleteAllInBatch()

        adminUser =
            userRepository.save(
                User(
                    name = "관리자",
                    email = "admin-${UUID.randomUUID()}@example.com",
                    provider = OAuthProvider.GOOGLE,
                    identifier = "admin-id-${UUID.randomUUID()}",
                    role = UserRole.ADMIN,
                    profileImageUrl = "https://example.com/admin-profile.jpg",
                ),
            )
        targetUser =
            userRepository.save(
                User(
                    name = "대상사용자",
                    email = "target-${UUID.randomUUID()}@example.com",
                    provider = OAuthProvider.GOOGLE,
                    identifier = "target-id-${UUID.randomUUID()}",
                    role = UserRole.USER,
                    profileImageUrl = "https://example.com/target-profile.jpg",
                ),
            )
        normalUser =
            userRepository.save(
                User(
                    name = "일반사용자",
                    email = "user-${UUID.randomUUID()}@example.com",
                    provider = OAuthProvider.GOOGLE,
                    identifier = "user-id-${UUID.randomUUID()}",
                    role = UserRole.USER,
                    profileImageUrl = "https://example.com/user-profile.jpg",
                ),
            )

        adminAccessToken = jwtTokenProvider.createAccessToken(adminUser.id!!, adminUser.role)
        userAccessToken = jwtTokenProvider.createAccessToken(normalUser.id!!, normalUser.role)
    }

    @Test
    @DisplayName("ADMIN 권한은 다른 사용자를 ADMIN으로 승격할 수 있다")
    fun adminCanPromoteUserToAdmin() {
        given()
            .contentType("application/json")
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, adminAccessToken)
            .body("""{"role":"ADMIN"}""")
            .`when`()
            .patch("/admin/users/${targetUser.id}/role")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.userId", equalTo(targetUser.id.toString()))
            .body("data.role", equalTo(UserRole.ADMIN.name))

        val updatedUser = userRepository.findById(targetUser.id!!).orElseThrow()
        assertEquals(UserRole.ADMIN, updatedUser.role)
    }

    @Test
    @DisplayName("USER 권한은 관리자 역할 변경 API를 호출할 수 없다")
    fun userCannotUpdateUserRole() {
        given()
            .contentType("application/json")
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, userAccessToken)
            .body("""{"role":"ADMIN"}""")
            .`when`()
            .patch("/admin/users/${targetUser.id}/role")
            .then()
            .statusCode(HttpStatus.FORBIDDEN.value())
    }
}
