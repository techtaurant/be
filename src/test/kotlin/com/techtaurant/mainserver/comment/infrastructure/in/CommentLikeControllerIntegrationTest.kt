package com.techtaurant.mainserver.comment.infrastructure.`in`

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.comment.application.CommentLikeLogService
import com.techtaurant.mainserver.comment.dto.RecordCommentLikeRequest
import com.techtaurant.mainserver.comment.entity.Comment
import com.techtaurant.mainserver.comment.infrastructure.out.CommentLikeLogRepository
import com.techtaurant.mainserver.comment.infrastructure.out.CommentRepository
import com.techtaurant.mainserver.common.enums.LikeStatus
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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.util.UUID

/**
 * CommentLikeController 통합 테스트
 *
 * Testcontainers를 활용하여 실제 PostgreSQL 데이터베이스 환경에서 테스트합니다.
 */
@DisplayName("CommentLikeController 통합 테스트")
class CommentLikeControllerIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var postRepository: PostRepository

    @Autowired
    private lateinit var categoryRepository: CategoryRepository

    @Autowired
    private lateinit var commentRepository: CommentRepository

    @Autowired
    private lateinit var commentLikeLogRepository: CommentLikeLogRepository

    @Autowired
    private lateinit var commentLikeLogService: CommentLikeLogService

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    private lateinit var testUser: User
    private lateinit var testPost: Post
    private lateinit var testComment: Comment
    private lateinit var accessToken: String

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

        // Given - 테스트 댓글 생성
        testComment =
            commentRepository.save(
                Comment(
                    content = "테스트 댓글입니다",
                    post = testPost,
                    author = testUser,
                    parent = null,
                    depth = 0,
                ),
            )

        // Given - JWT 토큰 생성
        accessToken = jwtTokenProvider.createAccessToken(testUser.id!!, testUser.role)
    }

    @Test
    @DisplayName("댓글 좋아요 성공 - 유효한 요청으로 좋아요 기록")
    fun recordLike_withValidRequest_shouldRecordLike() {
        // Given - 좋아요 요청 데이터
        val request = RecordCommentLikeRequest(likeStatus = LikeStatus.LIKE)

        // When - 좋아요 요청
        val response =
            given()
                .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
                .contentType(ContentType.JSON)
                .body(request)
                .`when`()
                .post("/api/comments/${testComment.id}/like")

        // Then - 200 OK 응답
        response.then().statusCode(HttpStatus.OK.value())

        // Then - 댓글의 likeCount 증가 확인
        val updatedComment = commentRepository.findById(testComment.id!!).get()
        assertThat(updatedComment.likeCount).isEqualTo(1)

        // Then - 좋아요 로그 생성 확인
        val log = commentLikeLogRepository.findByCommentIdAndUserId(testComment.id!!, testUser.id!!)
        assertThat(log).isNotNull
        assertThat(log?.isLiked).isTrue()
    }

    @Test
    @DisplayName("댓글 좋아요 성공 - 싫어요로 변경")
    fun recordLike_withDislikeRequest_shouldChangeToDislike() {
        // Given - 이미 좋아요한 상태 (likeCount = 1)
        commentLikeLogService.recordLike(testComment.id!!, testUser.id!!, LikeStatus.LIKE)
        val request = RecordCommentLikeRequest(likeStatus = LikeStatus.DISLIKE)

        // When - 싫어요로 변경 요청
        val response =
            given()
                .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
                .contentType(ContentType.JSON)
                .body(request)
                .`when`()
                .post("/api/comments/${testComment.id}/like")

        // Then - 200 OK 응답
        response.then().statusCode(HttpStatus.OK.value())

        // Then - 댓글의 likeCount 확인 (좋아요 취소 -1 + 싫어요 적용 -1 = -2)
        val updatedComment = commentRepository.findById(testComment.id!!).get()
        assertThat(updatedComment.likeCount).isEqualTo(-1)

        // Then - 싫어요 로그 업데이트 확인
        val log = commentLikeLogRepository.findByCommentIdAndUserId(testComment.id!!, testUser.id!!)
        assertThat(log).isNotNull
        assertThat(log?.isLiked).isFalse()
    }

    @Test
    @DisplayName("댓글 좋아요 성공 - 중복 좋아요 무시")
    fun recordLike_withDuplicateRequest_shouldIgnore() {
        // Given - 이미 좋아요한 상태
        commentLikeLogService.recordLike(testComment.id!!, testUser.id!!, LikeStatus.LIKE)
        val initialLikeCount = commentRepository.findById(testComment.id!!).get().likeCount
        val request = RecordCommentLikeRequest(likeStatus = LikeStatus.LIKE)

        // When - 다시 좋아요 요청
        val response =
            given()
                .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
                .contentType(ContentType.JSON)
                .body(request)
                .`when`()
                .post("/api/comments/${testComment.id}/like")

        // Then - 200 OK 응답
        response.then().statusCode(HttpStatus.OK.value())

        // Then - likeCount 변화 없음
        val updatedComment = commentRepository.findById(testComment.id!!).get()
        assertThat(updatedComment.likeCount).isEqualTo(initialLikeCount)
    }

    @Test
    @DisplayName("댓글 좋아요 실패 - 인증되지 않은 사용자")
    fun recordLike_withoutAuthentication_shouldReturnUnauthorized() {
        // Given - 좋아요 요청 데이터
        val request = RecordCommentLikeRequest(likeStatus = LikeStatus.LIKE)

        // When - 인증 헤더 없이 요청
        val response =
            given()
                .contentType(ContentType.JSON)
                .body(request)
                .`when`()
                .post("/api/comments/${testComment.id}/like")

        // Then - 401 Unauthorized 응답
        response.then().statusCode(HttpStatus.UNAUTHORIZED.value())
    }

    @Test
    @DisplayName("댓글 좋아요 실패 - 존재하지 않는 댓글")
    fun recordLike_withNonExistentComment_shouldReturnNotFound() {
        // Given - 존재하지 않는 댓글 ID
        val nonExistentCommentId = UUID.randomUUID()
        val request = RecordCommentLikeRequest(likeStatus = LikeStatus.LIKE)

        // When - 좋아요 요청
        val response =
            given()
                .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
                .contentType(ContentType.JSON)
                .body(request)
                .`when`()
                .post("/api/comments/$nonExistentCommentId/like")

        // Then - 404 Not Found 응답
        response.then().statusCode(HttpStatus.NOT_FOUND.value())
    }
}
