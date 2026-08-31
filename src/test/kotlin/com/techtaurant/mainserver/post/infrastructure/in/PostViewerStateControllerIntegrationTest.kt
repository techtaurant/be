package com.techtaurant.mainserver.post.infrastructure.`in`

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.entity.PostLikeLog
import com.techtaurant.mainserver.post.entity.PostReadLog
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.post.infrastructure.out.PostLikeLogRepository
import com.techtaurant.mainserver.post.infrastructure.out.PostReadLogRepository
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.entity.UserBan
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserBanRepository
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.util.UUID

@DisplayName("PostViewerStateController 통합 테스트")
class PostViewerStateControllerIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var userBanRepository: UserBanRepository

    @Autowired
    private lateinit var postRepository: PostRepository

    @Autowired
    private lateinit var postReadLogRepository: PostReadLogRepository

    @Autowired
    private lateinit var postLikeLogRepository: PostLikeLogRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    private lateinit var viewer: User
    private lateinit var author: User
    private lateinit var bannedAuthor: User
    private lateinit var accessToken: String

    @BeforeEach
    fun setUpTestData() {
        userBanRepository.deleteAllInBatch()
        postLikeLogRepository.deleteAllInBatch()
        postReadLogRepository.deleteAllInBatch()
        postRepository.deleteAllInBatch()
        userRepository.deleteAllInBatch()

        viewer = createUser("조회자")
        author = createUser("작성자")
        bannedAuthor = createUser("차단 작성자")
        accessToken = jwtTokenProvider.createAccessToken(viewer.id!!, viewer.role)
    }

    @Test
    @DisplayName("비로그인 사용자는 게시물 사용자 상태를 조회할 수 없다")
    fun getPostViewerStates_anonymous_returnsUnauthorized() {
        // given
        val post = createPost(author)

        // when & then
        given()
            .queryParam("postIds", post.id)
            .`when`()
            .get("/api/posts/me/states")
            .then()
            .statusCode(HttpStatus.UNAUTHORIZED.value())
    }

    @Test
    @DisplayName("로그인 사용자는 게시물별 읽음, 좋아요, 차단 상태를 조회한다")
    fun getPostViewerStates_authenticated_returnsViewerStates() {
        // given
        val readPost = createPost(author)
        val bannedPost = createPost(bannedAuthor)
        postReadLogRepository.save(PostReadLog(postId = readPost.id!!, user = viewer))
        postLikeLogRepository.save(PostLikeLog(post = readPost, user = viewer, isLiked = true))
        userBanRepository.save(UserBan(user = viewer, bannedUser = bannedAuthor))

        // when & then
        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .queryParam("postIds", bannedPost.id, readPost.id)
            .`when`()
            .get("/api/posts/me/states")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.find { it.postId == '${bannedPost.id}' }.isBanned", equalTo(true))
            .body("data.find { it.postId == '${bannedPost.id}' }.isRead", equalTo(false))
            .body("data.find { it.postId == '${bannedPost.id}' }.likeStatus", equalTo("NONE"))
            .body("data.find { it.postId == '${readPost.id}' }.isBanned", equalTo(false))
            .body("data.find { it.postId == '${readPost.id}' }.isRead", equalTo(true))
            .body("data.find { it.postId == '${readPost.id}' }.likeStatus", equalTo("LIKE"))
    }

    private fun createUser(name: String): User =
        userRepository.save(
            User(
                name = name,
                email = "${UUID.randomUUID()}@example.com",
                provider = OAuthProvider.GOOGLE,
                identifier = "google-${UUID.randomUUID()}",
                role = UserRole.USER,
                profileImageUrl = "https://example.com/profile.jpg",
            ),
        )

    private fun createPost(author: User): Post =
        postRepository.save(
            Post(
                title = "게시물",
                content = "본문",
                author = author,
                status = PostStatusEnum.PUBLISHED,
            ),
        )
}
