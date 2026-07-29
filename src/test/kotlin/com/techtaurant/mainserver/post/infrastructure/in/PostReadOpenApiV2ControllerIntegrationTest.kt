package com.techtaurant.mainserver.post.infrastructure.`in`

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.util.UUID

@DisplayName("PostReadOpenApiV2Controller 통합 테스트")
class PostReadOpenApiV2ControllerIntegrationTest : IntegrationTest() {
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
    @DisplayName("v2 게시물 목록은 정적 콘텐츠 필드만 반환한다")
    fun getPostContents_returnsStaticFieldsOnly() {
        // given
        val post =
            postRepository.save(
                Post(
                    title = "정적 콘텐츠 게시물",
                    content = "본문",
                    author = author,
                    viewCount = 10,
                    likeCount = 2,
                    commentCount = 1,
                    status = PostStatusEnum.PUBLISHED,
                ),
            )

        // when & then
        given()
            .`when`()
            .get("/open-api/v2/posts")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.content[0].id", equalTo(post.id.toString()))
            .body("data.content[0].title", equalTo("정적 콘텐츠 게시물"))
            .body("data.content[0].viewCount", nullValue())
            .body("data.content[0].likeCount", nullValue())
            .body("data.content[0].commentCount", nullValue())
            .body("data.content[0].status", nullValue())
            .body("data.content[0].thumbnailUrl", nullValue())
            .body("data.content[0].authorName", nullValue())
            .body("data.content[0].authorProfileImageUrl", nullValue())
            .body("data.content[0].isRead", nullValue())
    }

    @Test
    @DisplayName("v2 게시물 상세 조회는 조회수를 증가시키지 않는다")
    fun getPostContentDetail_doesNotIncrementViewCount() {
        // given
        val post =
            postRepository.save(
                Post(
                    title = "상세 게시물",
                    content = "본문",
                    author = author,
                    viewCount = 0,
                    status = PostStatusEnum.PUBLISHED,
                ),
            )

        // when & then
        given()
            .`when`()
            .get("/open-api/v2/posts/${post.id}")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.id", equalTo(post.id.toString()))
            .body("data.author.name", nullValue())
            .body("data.viewCount", nullValue())
            .body("data.attachmentPresignedUrls", nullValue())

        val updatedPost = postRepository.findById(post.id!!).orElseThrow()
        org.assertj.core.api.Assertions.assertThat(updatedPost.viewCount).isZero()
    }

    @Test
    @DisplayName("v2 공개 목록과 상세는 PUBLISHED 게시물만 반환한다")
    fun getPostContents_excludesNonPublishedPosts() {
        // given
        val publishedPost =
            postRepository.save(
                Post(
                    title = "공개 게시물",
                    content = "본문",
                    author = author,
                    status = PostStatusEnum.PUBLISHED,
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

        // when & then
        given()
            .`when`()
            .get("/open-api/v2/posts")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.content.id", hasItem(publishedPost.id.toString()))
            .body("data.content.id", not(hasItem(privatePost.id.toString())))

        given()
            .`when`()
            .get("/open-api/v2/posts/${privatePost.id}")
            .then()
            .statusCode(HttpStatus.NOT_FOUND.value())
    }

    @Test
    @DisplayName("v2 게시물 목록은 제목과 본문에 포함된 검색어로 필터링한다")
    fun getPostContents_withKeyword_filtersByTitleAndContent() {
        // given
        val titleMatch = createPublishedPost(title = "전문검색엔진 구축기", content = "본문")
        val contentMatch = createPublishedPost(title = "캐시 전략", content = "본문에서 검색 성능을 다룬다")
        val unrelatedPost = createPublishedPost(title = "인덱스 튜닝", content = "무관한 본문")

        // when
        val matchedIds =
            given()
                .queryParam("keyword", "검색")
                .`when`()
                .get("/open-api/v2/posts")
                .then()
                .statusCode(HttpStatus.OK.value())
                .extract()
                .path<List<String>>("data.content.id")

        // then
        assertThat(matchedIds).containsExactlyInAnyOrder(
            titleMatch.id.toString(),
            contentMatch.id.toString(),
        )
        assertThat(matchedIds).doesNotContain(unrelatedPost.id.toString())
    }

    @Test
    @DisplayName("v2 게시물 목록의 한 글자 검색어는 400을 반환한다")
    fun getPostContents_withSingleCharacterKeyword_returnsBadRequest() {
        given()
            .queryParam("keyword", "검")
            .`when`()
            .get("/open-api/v2/posts")
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())
    }

    @Test
    @DisplayName("v2 게시물 목록 검색은 대소문자를 구분하지 않는다")
    fun getPostContents_withKeyword_ignoresCase() {
        // given
        val matchingPost = createPublishedPost(title = "spring boot 입문", content = "본문")

        // when & then
        given()
            .queryParam("keyword", "SPRING")
            .`when`()
            .get("/open-api/v2/posts")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.content.id", hasItem(matchingPost.id.toString()))
    }

    @Test
    @DisplayName("v2 게시물 목록 검색은 퍼센트 기호를 일반 문자로 취급한다")
    fun getPostContents_withPercentInKeyword_treatsItAsLiteral() {
        // given
        val literalMatch = createPublishedPost(title = "100% 할인 정보", content = "본문")
        val wildcardOnlyMatch = createPublishedPost(title = "1000원 할인 정보", content = "본문")

        // when
        val matchedIds =
            given()
                .queryParam("keyword", "100%")
                .`when`()
                .get("/open-api/v2/posts")
                .then()
                .statusCode(HttpStatus.OK.value())
                .extract()
                .path<List<String>>("data.content.id")

        // then
        assertThat(matchedIds).containsExactly(literalMatch.id.toString())
        assertThat(matchedIds).doesNotContain(wildcardOnlyMatch.id.toString())
    }

    @Test
    @DisplayName("v2 게시물 목록 검색은 PUBLISHED 게시물만 반환한다")
    fun getPostContents_withKeyword_returnsPublishedPostsOnly() {
        // given
        val publishedPost = createPublishedPost(title = "검색 대상 공개글", content = "본문")
        val privatePost =
            postRepository.saveAndFlush(
                Post(
                    title = "검색 대상 비공개글",
                    content = "본문",
                    author = author,
                    status = PostStatusEnum.PRIVATE,
                ),
            )
        val draftPost =
            postRepository.saveAndFlush(
                Post(
                    title = "검색 대상 임시저장글",
                    content = "본문",
                    author = author,
                    status = PostStatusEnum.DRAFT,
                ),
            )

        // when
        val matchedIds =
            given()
                .queryParam("keyword", "검색")
                .`when`()
                .get("/open-api/v2/posts")
                .then()
                .statusCode(HttpStatus.OK.value())
                .extract()
                .path<List<String>>("data.content.id")

        // then
        assertThat(matchedIds).containsExactly(publishedPost.id.toString())
        assertThat(matchedIds).doesNotContain(privatePost.id.toString(), draftPost.id.toString())
    }

    @Test
    @DisplayName("v2 게시물 목록 검색은 커서로 다음 페이지를 중복 없이 조회한다")
    fun getPostContents_withKeyword_paginatesWithoutDuplication() {
        // given
        val oldestPost = createPublishedPost(title = "검색 결과 1", content = "본문")
        val middlePost = createPublishedPost(title = "검색 결과 2", content = "본문")
        val newestPost = createPublishedPost(title = "검색 결과 3", content = "본문")

        // when
        val firstPage =
            given()
                .queryParam("keyword", "검색")
                .queryParam("size", 2)
                .`when`()
                .get("/open-api/v2/posts")
                .then()
                .statusCode(HttpStatus.OK.value())
                .extract()
        val cursor = firstPage.path<String>("data.nextCursor")
        val secondPage =
            given()
                .queryParam("keyword", "검색")
                .queryParam("size", 2)
                .queryParam("cursor", cursor)
                .`when`()
                .get("/open-api/v2/posts")
                .then()
                .statusCode(HttpStatus.OK.value())
                .extract()

        // then
        assertThat(firstPage.path<Boolean>("data.hasNext")).isTrue()
        assertThat(cursor).isNotBlank()
        assertThat(firstPage.path<List<String>>("data.content.id")).containsExactly(
            newestPost.id.toString(),
            middlePost.id.toString(),
        )
        assertThat(secondPage.path<List<String>>("data.content.id")).containsExactly(oldestPost.id.toString())
        assertThat(secondPage.path<Boolean>("data.hasNext")).isFalse()
    }

    @Test
    @DisplayName("v2 게시물 목록 검색 결과가 없으면 빈 페이지를 반환한다")
    fun getPostContents_withUnmatchedKeyword_returnsEmptyPage() {
        // given
        createPublishedPost(title = "인덱스 튜닝", content = "본문")

        // when
        val response =
            given()
                .queryParam("keyword", "존재하지않는키워드")
                .`when`()
                .get("/open-api/v2/posts")
                .then()
                .statusCode(HttpStatus.OK.value())
                .extract()

        // then
        assertThat(response.path<List<String>>("data.content")).isEmpty()
        assertThat(response.path<Boolean>("data.hasNext")).isFalse()
        assertThat(response.path<String>("data.nextCursor")).isNull()
    }

    private fun createPublishedPost(
        title: String,
        content: String,
    ): Post =
        postRepository.saveAndFlush(
            Post(
                title = title,
                content = content,
                author = author,
                status = PostStatusEnum.PUBLISHED,
            ),
        )
}
