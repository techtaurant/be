package com.techtaurant.mainserver.post.infrastructure.`in`

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.post.dto.RecordPostReadRequest
import com.techtaurant.mainserver.post.entity.Category
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.entity.PostReadLog
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.post.infrastructure.out.CategoryRepository
import com.techtaurant.mainserver.post.infrastructure.out.PostReadLogRepository
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.restassured.RestAssured
import io.restassured.common.mapper.TypeRef
import io.restassured.http.ContentType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisplayName("게시물 읽음 표시 API")
class PostReadLogControllerTest : IntegrationTest() {
    @Autowired
    private lateinit var postReadLogRepository: PostReadLogRepository

    @Autowired
    private lateinit var postRepository: PostRepository

    @Autowired
    private lateinit var categoryRepository: CategoryRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    private lateinit var testUser: User
    private lateinit var testPost: Post
    private lateinit var accessToken: String

    @BeforeEach
    fun setup() {
        // Cleanup in correct order (foreign key constraints)
        postReadLogRepository.deleteAllInBatch()
        postRepository.deleteAllInBatch()
        categoryRepository.deleteAllInBatch()
        userRepository.deleteAllInBatch()

        // Create test user
        testUser =
            userRepository.save(
                User(
                    name = "Test User",
                    email = "test@example.com",
                    provider = OAuthProvider.GOOGLE,
                    identifier = "test-id-${UUID.randomUUID()}",
                    role = UserRole.USER,
                    profileImageUrl = "https://example.com/profile.jpg",
                ),
            )

        // Create test category
        val testCategory =
            categoryRepository.save(
                Category(
                    user = testUser,
                    name = "테스트 카테고리",
                    path = "테스트 카테고리",
                    depth = 1,
                ),
            )

        // Create test post
        testPost =
            postRepository.save(
                Post(
                    title = "테스트 게시물",
                    content = "테스트 내용",
                    author = testUser,
                    category = testCategory,
                    status = PostStatusEnum.PUBLISHED,
                ),
            )

        // Generate JWT token
        accessToken = jwtTokenProvider.createAccessToken(testUser.id!!, testUser.role)
    }

    @Test
    @DisplayName("읽음 표시 성공 - isRead=true로 요청하면 읽음 기록이 생성된다")
    fun toggleReadStatus_markAsRead_shouldCreateReadLog() {
        // Given: 읽음 기록이 없는 상태
        val request = RecordPostReadRequest(isRead = true)

        // When: POST /api/posts/{postId}/read-logs 호출
        val response =
            RestAssured
                .given()
                .contentType(ContentType.JSON)
                .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
                .body(request)
                .`when`()
                .post("/api/posts/${testPost.id}/read-logs")
                .then()
                .statusCode(200)
                .extract()
                .`as`(object : TypeRef<ApiResponse<Unit>>() {})

        // Then: 응답 성공 확인
        assertNotNull(response)
        assertEquals(200, response.status)

        // Then: DB에 읽음 기록 생성 확인
        val readLog = postReadLogRepository.findByPostIdAndUserId(testPost.id!!, testUser.id!!)
        assertNotNull(readLog, "읽음 기록이 DB에 생성되어야 함")
    }

    @Test
    @DisplayName("안읽음 표시 성공 - isRead=false로 요청하면 읽음 기록이 삭제된다")
    fun toggleReadStatus_markAsUnread_shouldDeleteReadLog() {
        // Given: 사용자가 게시물을 읽음 표시한 상태
        val readLog =
            postReadLogRepository.save(
                PostReadLog(
                    postId = testPost.id!!,
                    user = testUser,
                ),
            )
        val readLogId = readLog.id!!
        val request = RecordPostReadRequest(isRead = false)

        // When: POST /api/posts/{postId}/read-logs 호출 (isRead=false)
        val response =
            RestAssured
                .given()
                .contentType(ContentType.JSON)
                .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
                .body(request)
                .`when`()
                .post("/api/posts/${testPost.id}/read-logs")
                .then()
                .statusCode(200)
                .extract()
                .`as`(object : TypeRef<ApiResponse<Unit>>() {})

        // Then: 응답 성공 확인
        assertNotNull(response)
        assertEquals(200, response.status)

        // Then: DB에서 읽음 기록이 삭제되었는지 확인
        val deletedLog = postReadLogRepository.findById(readLogId)
        assertFalse(deletedLog.isPresent, "읽음 기록이 DB에서 삭제되어야 함")
    }

    @Test
    @DisplayName("읽음 표시 실패 - 존재하지 않는 게시물 ID로 요청하면 404 반환")
    fun toggleReadStatus_withNonExistentPost_shouldReturn404() {
        // Given: 존재하지 않는 게시물 ID
        val nonExistentPostId = UUID.randomUUID()
        val request = RecordPostReadRequest(isRead = true)

        // When: POST 요청
        val response =
            RestAssured
                .given()
                .contentType(ContentType.JSON)
                .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
                .body(request)
                .`when`()
                .post("/api/posts/$nonExistentPostId/read-logs")
                .then()
                .statusCode(404)
                .extract()
                .`as`(object : TypeRef<ApiResponse<Unit>>() {})

        // Then: 에러 응답 확인
        assertNotNull(response)
        assertEquals(2001, response.status, "POST_NOT_FOUND 에러 코드 (2001)가 반환되어야 함")
        assertNotNull(response.message)
    }

    @Test
    @DisplayName("읽음 표시 실패 - JWT 토큰 없이 요청하면 401 반환")
    fun toggleReadStatus_withoutAuthentication_shouldReturn401() {
        // Given: 읽음 요청
        val request = RecordPostReadRequest(isRead = true)

        // When: 인증 헤더 없이 POST 요청
        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(request)
            .`when`()
            .post("/api/posts/${testPost.id}/read-logs")
            .then()
            .statusCode(401)
    }

    @Test
    @DisplayName("멱등성 보장 - 이미 읽음 상태에서 다시 읽음 요청 시 정상 처리")
    fun toggleReadStatus_duplicateRead_shouldSucceed() {
        // Given: 이미 읽음 표시한 상태
        postReadLogRepository.save(
            PostReadLog(
                postId = testPost.id!!,
                user = testUser,
            ),
        )
        val request = RecordPostReadRequest(isRead = true)

        // When: 다시 읽음 표시 요청
        val response =
            RestAssured
                .given()
                .contentType(ContentType.JSON)
                .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
                .body(request)
                .`when`()
                .post("/api/posts/${testPost.id}/read-logs")
                .then()
                .statusCode(200)
                .extract()
                .`as`(object : TypeRef<ApiResponse<Unit>>() {})

        // Then: 정상 응답
        assertNotNull(response)
        assertEquals(200, response.status)

        // Then: 읽음 기록은 여전히 1개
        val exists = postReadLogRepository.existsByPostIdAndUserId(testPost.id!!, testUser.id!!)
        assertTrue(exists, "읽음 기록이 유지되어야 함")
    }
}
