package com.techtaurant.mainserver.user.infrastructure.`in`

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.util.UUID

@DisplayName("UserPostOpenApiV2Controller 통합 테스트")
class UserPostOpenApiV2ControllerIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var postRepository: PostRepository

    private lateinit var author: User

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
    }

    @Test
    @DisplayName("v2 사용자 게시물 목록 호환 경로는 PUBLISHED 정적 콘텐츠만 반환한다")
    fun getPostContentsByUserId_returnsPublishedStaticContentOnly() {
        val publishedPost =
            postRepository.save(
                Post(
                    title = "공개 게시물",
                    content = "본문",
                    author = author,
                    status = PostStatusEnum.PUBLISHED,
                    viewCount = 10,
                ),
            )
        val privatePost =
            postRepository.save(
                Post(
                    title = "비공개 게시물",
                    content = "본문",
                    author = author,
                    status = PostStatusEnum.PRIVATE,
                ),
            )

        given()
            .`when`()
            .get("/open-api/v2/users/${author.id}/posts")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.content.id", hasItem(publishedPost.id.toString()))
            .body("data.content.id", not(hasItem(privatePost.id.toString())))
            .body("data.content[0].viewCount", nullValue())
            .body("data.content[0].authorName", nullValue())
            .body("data.content[0].thumbnailUrl", nullValue())
    }
}
