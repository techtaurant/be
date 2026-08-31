package com.techtaurant.mainserver.comment.infrastructure.`in`

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.comment.dto.CreateCommentRequest
import com.techtaurant.mainserver.post.entity.Category
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.infrastructure.out.CategoryRepository
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.util.UUID

/**
 * CommentController 통합 테스트
 *
 * Testcontainers를 활용하여 실제 PostgreSQL 데이터베이스 환경에서 테스트합니다.
 */
@DisplayName("CommentController 통합 테스트")
class CommentControllerIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var postRepository: PostRepository

    @Autowired
    private lateinit var categoryRepository: CategoryRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    private lateinit var testUser: User
    private lateinit var testPost: Post
    private lateinit var accessToken: String

    @BeforeEach
    fun setUpTestData() {
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

        val testCategory =
            categoryRepository.save(
                Category(
                    user = testUser,
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
                    author = testUser,
                    category = testCategory,
                ),
            )

        accessToken = jwtTokenProvider.createAccessToken(testUser.id!!, testUser.role)
    }

    @Test
    @DisplayName("댓글 작성 성공 - 유효한 요청으로 댓글을 생성한다")
    fun createComment_withValidRequest_shouldReturnCreated() {
        // Given - 댓글 작성 요청 데이터
        val request =
            CreateCommentRequest(
                content = "좋은 글이네요!",
                postId = testPost.id!!,
                parentId = null,
            )

        println("=== Test Debug ===")
        println("Access Token: $accessToken")
        println("Test User ID: ${testUser.id}")
        println("Request: $request")

        // When - 댓글 작성 API 호출
        given()
            .contentType(ContentType.JSON)
            .body(request)
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .log().all()
            .`when`()
            .post("/api/comments")
            .then()
            .log().all()
            .statusCode(HttpStatus.CREATED.value())
            .body("data.content", equalTo("좋은 글이네요!"))
            .body("data.postId", equalTo(testPost.id.toString()))
            .body("data.authorId", equalTo(testUser.id.toString()))
            .body("data.authorName", equalTo("테스트사용자"))
            .body("data.parentId", equalTo(null))
            .body("data.depth", equalTo(0))
            .body("data.id", notNullValue())
            .body("data.createdAt", notNullValue())
            .body("data.updatedAt", notNullValue())
    }

    @Test
    @DisplayName("댓글 작성 성공 - 유효한 요청으로 대댓글을 생성한다")
    fun createComment_withParentComment_shouldReturnCreatedReply() {
        // Given - 부모 댓글 생성
        val parentRequest =
            CreateCommentRequest(
                content = "부모 댓글입니다",
                postId = testPost.id!!,
                parentId = null,
            )

        val parentResponse =
            given()
                .contentType(ContentType.JSON)
                .body(parentRequest)
                .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
                .`when`()
                .post("/api/comments")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract()
                .path<String>("data.id")

        val parentCommentId = UUID.fromString(parentResponse)

        // Given - 대댓글 작성 요청 데이터
        val replyRequest =
            CreateCommentRequest(
                content = "대댓글입니다",
                postId = testPost.id!!,
                parentId = parentCommentId,
            )

        // When - 대댓글 작성 API 호출
        given()
            .contentType(ContentType.JSON)
            .body(replyRequest)
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .`when`()
            .post("/api/comments")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .body("data.content", equalTo("대댓글입니다"))
            .body("data.parentId", equalTo(parentCommentId.toString()))
            .body("data.depth", equalTo(1))
    }

    @Test
    @DisplayName("댓글 작성 실패 - 댓글 내용이 비어있으면 400 Bad Request와 Validation 에러 상세를 반환한다")
    fun createComment_withBlankContent_shouldReturnBadRequest() {
        // Given - 빈 내용의 댓글 작성 요청
        val request =
            CreateCommentRequest(
                content = "",
                postId = testPost.id!!,
                parentId = null,
            )

        // When - 댓글 작성 API 호출
        // Then - 400 Bad Request 응답과 Validation 에러 필드 확인
        given()
            .contentType(ContentType.JSON)
            .body(request)
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .`when`()
            .post("/api/comments")
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())
            .body("status", equalTo(400))
            .body("message", equalTo("Wrong Request"))
            .body("data.errors.content", notNullValue())
    }

    @Test
    @DisplayName("댓글 작성 실패 - 존재하지 않는 게시물에 댓글을 작성하면 404 Not Found와 에러 메시지를 반환한다")
    fun createComment_withNonExistentPost_shouldReturnNotFound() {
        // Given - 존재하지 않는 게시물 ID로 댓글 작성 요청
        val nonExistentPostId = UUID.randomUUID()
        val request =
            CreateCommentRequest(
                content = "댓글 내용",
                postId = nonExistentPostId,
                parentId = null,
            )

        // When - 댓글 작성 API 호출
        // Then - 404 Not Found 응답과 ApiException 에러 형식 확인
        given()
            .contentType(ContentType.JSON)
            .body(request)
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .`when`()
            .post("/api/comments")
            .then()
            .statusCode(HttpStatus.NOT_FOUND.value())
            .body("data", equalTo(null))
            .body("message", notNullValue())
    }

    @Test
    @DisplayName("댓글 작성 실패 - 존재하지 않는 부모 댓글에 대댓글을 작성하면 404 Not Found와 에러 메시지를 반환한다")
    fun createComment_withNonExistentParentComment_shouldReturnNotFound() {
        // Given - 존재하지 않는 부모 댓글 ID로 대댓글 작성 요청
        val nonExistentParentId = UUID.randomUUID()
        val request =
            CreateCommentRequest(
                content = "대댓글 내용",
                postId = testPost.id!!,
                parentId = nonExistentParentId,
            )

        // When - 대댓글 작성 API 호출
        // Then - 404 Not Found 응답과 ApiException 에러 형식 확인
        given()
            .contentType(ContentType.JSON)
            .body(request)
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .`when`()
            .post("/api/comments")
            .then()
            .statusCode(HttpStatus.NOT_FOUND.value())
            .body("data", equalTo(null))
            .body("message", notNullValue())
    }

    @Test
    @DisplayName("댓글 작성 실패 - 인증되지 않은 사용자가 댓글을 작성하면 401 Unauthorized를 반환한다")
    fun createComment_withoutAuthentication_shouldReturnUnauthorized() {
        // Given - 댓글 작성 요청 데이터
        val request =
            CreateCommentRequest(
                content = "댓글 내용",
                postId = testPost.id!!,
                parentId = null,
            )

        // When - X-User-Id 헤더 없이 댓글 작성 API 호출
        // Then - 401 Unauthorized 응답 확인
        given()
            .contentType(ContentType.JSON)
            .body(request)
            .`when`()
            .post("/api/comments")
            .then()
            .statusCode(HttpStatus.UNAUTHORIZED.value())
    }

    @Test
    @DisplayName("댓글 작성 실패 - 대댓글의 답글을 작성하려고 하면 400 Bad Request를 반환한다")
    fun createComment_withReplyToReply_shouldReturnBadRequest() {
        // Given - 부모 댓글 생성
        val parentRequest =
            CreateCommentRequest(
                content = "부모 댓글입니다",
                postId = testPost.id!!,
                parentId = null,
            )

        val parentResponse =
            given()
                .contentType(ContentType.JSON)
                .body(parentRequest)
                .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
                .`when`()
                .post("/api/comments")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract()
                .path<String>("data.id")

        val parentCommentId = UUID.fromString(parentResponse)

        // Given - 대댓글 생성
        val replyRequest =
            CreateCommentRequest(
                content = "대댓글입니다",
                postId = testPost.id!!,
                parentId = parentCommentId,
            )

        val replyResponse =
            given()
                .contentType(ContentType.JSON)
                .body(replyRequest)
                .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
                .`when`()
                .post("/api/comments")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract()
                .path<String>("data.id")

        val replyCommentId = UUID.fromString(replyResponse)

        // Given - 대댓글의 답글 작성 요청
        val replyToReplyRequest =
            CreateCommentRequest(
                content = "대댓글의 답글입니다",
                postId = testPost.id!!,
                parentId = replyCommentId,
            )

        // When - 대댓글의 답글 작성 API 호출
        // Then - 400 Bad Request 응답과 ApiException 에러 형식 확인
        given()
            .contentType(ContentType.JSON)
            .body(replyToReplyRequest)
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .`when`()
            .post("/api/comments")
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())
            .body("data", equalTo(null))
            .body("message", notNullValue())
    }

    @Test
    @DisplayName("댓글 작성 실패 - 부모 댓글이 다른 게시물에 속한 경우 400 Bad Request와 에러 메시지를 반환한다")
    fun createComment_withParentCommentFromDifferentPost_shouldReturnBadRequest() {
        // Given - 다른 게시물 생성
        val anotherPost =
            postRepository.save(
                Post(
                    title = "다른 게시물",
                    content = "다른 게시물 내용입니다",
                    author = testUser,
                    category = null,
                ),
            )

        // Given - 첫 번째 게시물에 댓글 생성
        val parentRequest =
            CreateCommentRequest(
                content = "첫 번째 게시물의 댓글",
                postId = testPost.id!!,
                parentId = null,
            )

        val parentResponse =
            given()
                .contentType(ContentType.JSON)
                .body(parentRequest)
                .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
                .`when`()
                .post("/api/comments")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract()
                .path<String>("data.id")

        val parentCommentId = UUID.fromString(parentResponse)

        // Given - 두 번째 게시물에 대해 첫 번째 게시물의 댓글을 부모로 하는 대댓글 작성 요청
        val invalidReplyRequest =
            CreateCommentRequest(
                content = "잘못된 대댓글",
                postId = anotherPost.id!!,
                parentId = parentCommentId,
            )

        // When - 대댓글 작성 API 호출
        // Then - 400 Bad Request 응답과 ApiException 에러 형식 확인
        given()
            .contentType(ContentType.JSON)
            .body(invalidReplyRequest)
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .`when`()
            .post("/api/comments")
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())
            .body("data", equalTo(null))
            .body("message", notNullValue())
    }
}
