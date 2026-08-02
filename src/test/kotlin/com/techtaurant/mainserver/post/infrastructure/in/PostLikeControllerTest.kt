package com.techtaurant.mainserver.post.infrastructure.`in`

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.enums.LikeStatus
import com.techtaurant.mainserver.post.dto.RecordPostLikeRequest
import com.techtaurant.mainserver.post.entity.Category
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.entity.PostLikeLog
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.post.infrastructure.out.CategoryRepository
import com.techtaurant.mainserver.post.infrastructure.out.PostLikeLogRepository
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
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

@DisplayName("게시물 좋아요 API")
class PostLikeControllerTest : IntegrationTest() {
    @Autowired
    private lateinit var postLikeLogRepository: PostLikeLogRepository

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
        postLikeLogRepository.deleteAllInBatch()
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
    @DisplayName("좋아요 취소 성공 - NONE 상태로 변경하면 로그가 DB에서 제거된다")
    fun recordLike_withNoneStatus_shouldDeleteLog() {
        // Given: 사용자가 게시물에 좋아요를 누른 상태
        val likeLog =
            postLikeLogRepository.save(
                PostLikeLog(
                    post = testPost,
                    user = testUser,
                    isLiked = true,
                ),
            )
        val likeLogId = likeLog.id!!
        val request = RecordPostLikeRequest(likeStatus = LikeStatus.NONE)

        // When: POST /api/posts/{postId}/like 호출 (NONE 상태)
        val response =
            RestAssured
                .given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer $accessToken")
                .body(request)
                .`when`()
                .post("/api/posts/${testPost.id}/like")
                .then()
                .statusCode(200)
                .extract()
                .`as`(object : TypeRef<ApiResponse<Unit>>() {})

        // Then: 응답 성공 확인
        assertNotNull(response)
        assertEquals(200, response.status)

        // Then: DB에서 좋아요 로그가 삭제되었는지 확인
        val deletedLog = postLikeLogRepository.findById(likeLogId)
        assertFalse(deletedLog.isPresent, "좋아요 로그가 DB에서 삭제되어야 함")
    }

    @Test
    @DisplayName("좋아요 취소 실패 - 존재하지 않는 게시물 ID로 요청하면 404 NOT_FOUND 반환")
    fun recordLike_withNonExistentPost_shouldReturn404() {
        // Given: 존재하지 않는 게시물 ID
        val nonExistentPostId = UUID.randomUUID()
        val request = RecordPostLikeRequest(likeStatus = LikeStatus.LIKE)

        // When: POST 요청
        val response =
            RestAssured
                .given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer $accessToken")
                .body(request)
                .`when`()
                .post("/api/posts/$nonExistentPostId/like")
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
    @DisplayName("좋아요 취소 - 이미 중립 상태에서 NONE 요청 시 정상 처리")
    fun recordLike_withNoneOnNeutral_shouldSucceed() {
        // Given: 좋아요를 누르지 않은 상태 (좋아요 로그 없음)
        val request = RecordPostLikeRequest(likeStatus = LikeStatus.NONE)

        // When: POST 요청
        val response =
            RestAssured
                .given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer $accessToken")
                .body(request)
                .`when`()
                .post("/api/posts/${testPost.id}/like")
                .then()
                .statusCode(200)
                .extract()
                .`as`(object : TypeRef<ApiResponse<Unit>>() {})

        // Then: 정상 응답
        assertNotNull(response)
        assertEquals(200, response.status)
    }

    @Test
    @DisplayName("좋아요 실패 - JWT 토큰 없이 요청하면 401 UNAUTHORIZED 반환")
    fun recordLike_withoutAuthentication_shouldReturn401() {
        // Given: 좋아요 요청
        val request = RecordPostLikeRequest(likeStatus = LikeStatus.LIKE)

        // When: 인증 헤더 없이 POST 요청
        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(request)
            .`when`()
            .post("/api/posts/${testPost.id}/like")
            .then()
            .statusCode(401)
    }
}
