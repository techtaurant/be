package com.techtaurant.mainserver.user.infrastructure.`in`

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.util.UUID

@DisplayName("UserPostController 통합 테스트")
class UserPostControllerIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var postRepository: PostRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    private lateinit var author: User
    private lateinit var otherUser: User
    private lateinit var authorAccessToken: String

    @BeforeEach
    fun setUpTestData() {
        author = createUser("작성자")
        otherUser = createUser("다른 사용자")
        authorAccessToken = jwtTokenProvider.createAccessToken(author.id!!, author.role)
    }

    @Test
    @DisplayName("비로그인 사용자는 내 게시물 목록을 조회할 수 없다")
    fun getMyPosts_anonymous_returnsUnauthorized() {
        // when & then
        given()
            .`when`()
            .get("/api/users/me/posts")
            .then()
            .statusCode(HttpStatus.UNAUTHORIZED.value())
    }

    @Test
    @DisplayName("내 게시물 목록은 내 PUBLISHED/PRIVATE 게시물만 반환한다")
    fun getMyPosts_returnsOwnPublishedAndPrivatePostsOnly() {
        // given
        val publishedPost = createPost(author, "공개 게시물", PostStatusEnum.PUBLISHED)
        val privatePost = createPost(author, "비공개 게시물", PostStatusEnum.PRIVATE)
        val draftPost = createPost(author, "임시 저장 게시물", PostStatusEnum.DRAFT)
        val otherPost = createPost(otherUser, "다른 사용자 게시물", PostStatusEnum.PUBLISHED)

        // when & then
        given()
            .header("Authorization", "Bearer $authorAccessToken")
            .`when`()
            .get("/api/users/me/posts")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.content.id", hasItems(publishedPost.id.toString(), privatePost.id.toString()))
            .body("data.content.id", not(hasItem(draftPost.id.toString())))
            .body("data.content.id", not(hasItem(otherPost.id.toString())))
            .body("data.content.find { it.id == '${publishedPost.id}' }.status", equalTo("PUBLISHED"))
            .body("data.content.find { it.id == '${privatePost.id}' }.status", equalTo("PRIVATE"))
            .body("data.content.find { it.id == '${privatePost.id}' }.authorName", equalTo(author.name))
            .body("data.content.find { it.id == '${privatePost.id}' }.viewCount", equalTo(0))
    }

    @Test
    @DisplayName("내 게시물 상세는 PRIVATE 게시물을 반환하고 조회수를 증가시키지 않는다")
    fun getMyPostDetail_privatePost_returnsDetailWithoutIncrementingViewCount() {
        // given
        val privatePost =
            postRepository.save(
                Post(
                    title = "비공개 게시물",
                    content = "비공개 본문",
                    author = author,
                    status = PostStatusEnum.PRIVATE,
                    viewCount = 7,
                ),
            )

        // when & then
        given()
            .header("Authorization", "Bearer $authorAccessToken")
            .`when`()
            .get("/api/users/me/posts/${privatePost.id}")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.id", equalTo(privatePost.id.toString()))
            .body("data.content", equalTo("비공개 본문"))
            .body("data.status", equalTo("PRIVATE"))
            .body("data.author.id", equalTo(author.id.toString()))
            .body("data.author.name", equalTo(author.name))
            .body("data.viewCount", equalTo(7))
            .body("data.attachmentPresignedUrls", hasSize<Any>(0))

        val updatedPost = postRepository.findById(privatePost.id!!).orElseThrow()
        assertThat(updatedPost.viewCount).isEqualTo(7)
    }

    @Test
    @DisplayName("다른 사용자의 게시물은 내 게시물 상세에서 조회할 수 없다")
    fun getMyPostDetail_otherUserPost_returnsNotFound() {
        // given
        val otherPost = createPost(otherUser, "다른 사용자 게시물", PostStatusEnum.PUBLISHED)

        // when & then
        given()
            .header("Authorization", "Bearer $authorAccessToken")
            .`when`()
            .get("/api/users/me/posts/${otherPost.id}")
            .then()
            .statusCode(HttpStatus.NOT_FOUND.value())
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

    private fun createPost(
        author: User,
        title: String,
        status: PostStatusEnum,
    ): Post =
        postRepository.save(
            Post(
                title = title,
                content = "본문",
                author = author,
                status = status,
            ),
        )
}
