package com.techtaurant.mainserver.security.config

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.security.SecurityConstants
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
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@DisplayName("SecurityConfig admin prefix 통합 테스트")
@Import(SecurityConfigAdminPrefixIntegrationTest.AdminTestControllerConfig::class)
class SecurityConfigAdminPrefixIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    private lateinit var userAccessToken: String
    private lateinit var adminAccessToken: String
    private lateinit var userId: UUID
    private lateinit var adminId: UUID

    @BeforeEach
    fun setUpUsers() {
        userRepository.deleteAllInBatch()

        val user =
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
        val admin =
            userRepository.save(
                User(
                    name = "관리자사용자",
                    email = "admin-${UUID.randomUUID()}@example.com",
                    provider = OAuthProvider.GOOGLE,
                    identifier = "admin-id-${UUID.randomUUID()}",
                    role = UserRole.ADMIN,
                    profileImageUrl = "https://example.com/admin-profile.jpg",
                ),
            )

        userId = user.id!!
        adminId = admin.id!!
        userAccessToken = jwtTokenProvider.createAccessToken(userId, user.role)
        adminAccessToken = jwtTokenProvider.createAccessToken(adminId, admin.role)
    }

    @Test
    @DisplayName("USER 권한은 일반 사용자 API를 호출할 수 있다")
    fun userCanAccessUserApi() {
        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, userAccessToken)
            .`when`()
            .get("/api/users/me")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.id", equalTo(userId.toString()))
    }

    @Test
    @DisplayName("ADMIN 권한은 일반 사용자 API를 호출할 수 있다")
    fun adminCanAccessUserApi() {
        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, adminAccessToken)
            .`when`()
            .get("/api/users/me")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.id", equalTo(adminId.toString()))
    }

    @Test
    @DisplayName("USER 권한은 admin prefix API에 접근하면 403을 반환한다")
    fun userCannotAccessAdminPrefixApi() {
        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, userAccessToken)
            .`when`()
            .get("${SecurityConstants.ADMIN_API_PREFIX}/test/ping")
            .then()
            .statusCode(HttpStatus.FORBIDDEN.value())
    }

    @Test
    @DisplayName("ADMIN 권한은 admin prefix API 보안 검사를 통과한다")
    fun adminPassesAdminPrefixSecurity() {
        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, adminAccessToken)
            .`when`()
            .get("${SecurityConstants.ADMIN_API_PREFIX}/test/ping")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("message", equalTo("pong"))
    }

    @TestConfiguration
    class AdminTestControllerConfig {
        @Bean
        fun adminTestController(): AdminTestController = AdminTestController()
    }

    @RestController
    @RequestMapping("${SecurityConstants.ADMIN_API_PREFIX}/test")
    class AdminTestController {
        @GetMapping("/ping")
        fun ping(): Map<String, String> = mapOf("message" to "pong")
    }
}
