package com.techtaurant.mainserver.post.infrastructure.`in`

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.util.UUID

@DisplayName("PostViewLogOpenApiController 통합 테스트")
class PostViewLogOpenApiControllerIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var postRepository: PostRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    private lateinit var author: User
    private lateinit var accessToken: String

    @BeforeEach
    fun setUpTestData() {
        postRepository.deleteAllInBatch()
        userRepository.deleteAllInBatch()

        author =
            userRepository.save(
                User(
                    name = "작성자",
                    email = "writer-${UUID.randomUUID()}@example.com",
                    provider = OAuthProvider.GOOGLE,
                    identifier = "writer-${UUID.randomUUID()}",
                    role = UserRole.USER,
                    profileImageUrl = "https://example.com/profile.jpg",
                ),
            )
        accessToken = jwtTokenProvider.createAccessToken(author.id!!, author.role)
    }

    @Test
    @DisplayName("비로그인 사용자도 조회 로그를 기록하고 조회수를 증가시킬 수 있다")
    fun recordPostView_anonymous_incrementsViewCount() {
        // given
        val post = createPost()

        // when & then
        given()
            .header("X-Forwarded-For", "203.0.113.10")
            .header("User-Agent", "JUnit")
            .`when`()
            .post("/open-api/posts/${post.id}/view-logs")
            .then()
            .statusCode(HttpStatus.OK.value())

        val updatedPost = postRepository.findById(post.id!!).orElseThrow()
        assertThat(updatedPost.viewCount).isEqualTo(1)
    }

    @Test
    @DisplayName("로그인 사용자가 조회 로그를 기록하면 조회수가 증가한다")
    fun recordPostView_authenticated_incrementsViewCount() {
        // given
        val post = createPost()

        // when & then
        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .`when`()
            .post("/open-api/posts/${post.id}/view-logs")
            .then()
            .statusCode(HttpStatus.OK.value())

        val updatedPost = postRepository.findById(post.id!!).orElseThrow()
        assertThat(updatedPost.viewCount).isEqualTo(1)
    }

    @Test
    @DisplayName("존재하지 않는 게시물 조회 로그 기록 요청은 404를 반환한다")
    fun recordPostView_notFound_returnsNotFound() {
        // given
        val postId = UUID.randomUUID()

        // when & then
        given()
            .`when`()
            .post("/open-api/posts/$postId/view-logs")
            .then()
            .statusCode(HttpStatus.NOT_FOUND.value())
    }

    @Test
    @DisplayName("비로그인 사용자는 비공개 게시물 조회 로그를 기록할 수 없다")
    fun recordPostView_anonymousPrivatePost_returnsNotFound() {
        // given
        val post = createPost(status = PostStatusEnum.PRIVATE)

        // when & then
        given()
            .`when`()
            .post("/open-api/posts/${post.id}/view-logs")
            .then()
            .statusCode(HttpStatus.NOT_FOUND.value())

        val updatedPost = postRepository.findById(post.id!!).orElseThrow()
        assertThat(updatedPost.viewCount).isZero()
    }

    private fun createPost(status: PostStatusEnum = PostStatusEnum.PUBLISHED): Post =
        postRepository.save(
            Post(
                title = "조회 로그 게시물",
                content = "본문",
                author = author,
                status = status,
            ),
        )
}
