package com.techtaurant.mainserver.post.infrastructure.`in`

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
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

@DisplayName("게시물 검색 API 통합 테스트")
class PostSearchOpenApiControllerIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var postRepository: PostRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var testUser: User
    private lateinit var otherUser: User
    private lateinit var accessToken: String

    @BeforeEach
    fun setUpTestData() {
        postRepository.deleteAllInBatch()
        userRepository.deleteAllInBatch()

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

        otherUser =
            userRepository.save(
                User(
                    name = "다른사용자",
                    email = "other@example.com",
                    provider = OAuthProvider.GOOGLE,
                    identifier = "other-id-${UUID.randomUUID()}",
                    role = UserRole.USER,
                    profileImageUrl = "https://example.com/other-profile.jpg",
                ),
            )

        accessToken = jwtTokenProvider.createAccessToken(testUser.id!!, testUser.role)
    }

    @Test
    @DisplayName("단어 중간에 포함된 검색어도 제목에서 매칭된다")
    fun searchPosts_matchesKeywordInsideWord() {
        // given
        val matchingPost = createPublishedPost(title = "전문검색엔진 구축기", content = "본문")
        val unrelatedPost = createPublishedPost(title = "캐시 전략 정리", content = "본문")

        // when & then
        given()
            .queryParam("keyword", "검색")
            .`when`()
            .get("/open-api/posts/search")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.content.id", hasItem(matchingPost.id.toString()))
            .body("data.content.id", not(hasItem(unrelatedPost.id.toString())))
    }

    @Test
    @DisplayName("제목에 없고 본문에만 있는 검색어도 매칭된다")
    fun searchPosts_matchesKeywordInContentOnly() {
        // given
        val matchingPost = createPublishedPost(title = "제목에는 없는 단어", content = "본문에는 검색 이라는 단어가 들어있다")
        val unrelatedPost = createPublishedPost(title = "무관한 제목", content = "무관한 본문")

        // when & then
        given()
            .queryParam("keyword", "검색")
            .`when`()
            .get("/open-api/posts/search")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.content.id", hasItem(matchingPost.id.toString()))
            .body("data.content.id", not(hasItem(unrelatedPost.id.toString())))
    }

    @Test
    @DisplayName("두 글자 검색어로 어중 매칭 게시물이 모두 조회된다")
    fun searchPosts_twoCharacterKeyword_returnsAllMidWordMatches() {
        // given
        val leadingMatch = createPublishedPost(title = "검색 성능 개선", content = "본문")
        val middleMatch = createPublishedPost(title = "전문검색엔진 구축기", content = "본문")
        val trailingMatch = createPublishedPost(title = "풀텍스트검색", content = "본문")
        val unrelatedPost = createPublishedPost(title = "인덱스 튜닝", content = "본문")

        // when
        val matchedIds =
            given()
                .queryParam("keyword", "검색")
                .`when`()
                .get("/open-api/posts/search")
                .then()
                .statusCode(HttpStatus.OK.value())
                .extract()
                .path<List<String>>("data.content.id")

        // then
        assertThat(matchedIds).containsExactlyInAnyOrder(
            leadingMatch.id.toString(),
            middleMatch.id.toString(),
            trailingMatch.id.toString(),
        )
        assertThat(matchedIds).doesNotContain(unrelatedPost.id.toString())
    }

    @Test
    @DisplayName("한 글자 검색어는 400을 반환한다")
    fun searchPosts_singleCharacterKeyword_returnsBadRequest() {
        // given
        createPublishedPost(title = "검색 성능 개선", content = "본문")

        // when & then
        given()
            .queryParam("keyword", "검")
            .`when`()
            .get("/open-api/posts/search")
            .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())
    }

    @Test
    @DisplayName("검색어의 대소문자는 무시된다")
    fun searchPosts_ignoresKeywordCase() {
        // given
        val matchingPost = createPublishedPost(title = "spring boot 입문", content = "본문")

        // when & then
        given()
            .queryParam("keyword", "SPRING")
            .`when`()
            .get("/open-api/posts/search")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.content.id", hasItem(matchingPost.id.toString()))
    }

    @Test
    @DisplayName("검색어의 퍼센트 기호는 와일드카드가 아닌 일반 문자로 매칭된다")
    fun searchPosts_treatsPercentSignAsLiteral() {
        // given
        val literalMatch = createPublishedPost(title = "100% 할인 정보", content = "본문")
        val wildcardOnlyMatch = createPublishedPost(title = "1000원 할인 정보", content = "본문")

        // when
        val matchedIds =
            given()
                .queryParam("keyword", "100%")
                .`when`()
                .get("/open-api/posts/search")
                .then()
                .statusCode(HttpStatus.OK.value())
                .extract()
                .path<List<String>>("data.content.id")

        // then
        assertThat(matchedIds).containsExactly(literalMatch.id.toString())
        assertThat(matchedIds).doesNotContain(wildcardOnlyMatch.id.toString())
    }

    @Test
    @DisplayName("비로그인 검색은 PUBLISHED 게시물만 반환한다")
    fun searchPosts_anonymous_returnsPublishedOnly() {
        // given
        val publishedPost = createPost("검색 대상 공개글", testUser, PostStatusEnum.PUBLISHED)
        val privatePost = createPost("검색 대상 비공개글", testUser, PostStatusEnum.PRIVATE)
        val draftPost = createPost("검색 대상 임시저장글", testUser, PostStatusEnum.DRAFT)
        val otherPrivatePost = createPost("검색 대상 타인 비공개글", otherUser, PostStatusEnum.PRIVATE)

        // when
        val matchedIds =
            given()
                .queryParam("keyword", "검색")
                .`when`()
                .get("/open-api/posts/search")
                .then()
                .statusCode(HttpStatus.OK.value())
                .extract()
                .path<List<String>>("data.content.id")

        // then
        assertThat(matchedIds).containsExactly(publishedPost.id.toString())
        assertThat(matchedIds).doesNotContain(
            privatePost.id.toString(),
            draftPost.id.toString(),
            otherPrivatePost.id.toString(),
        )
    }

    @Test
    @DisplayName("로그인 검색은 본인의 PRIVATE 게시물을 포함하고 DRAFT는 제외한다")
    fun searchPosts_loggedIn_includesOwnPrivateButNotDraft() {
        // given
        val publishedPost = createPost("검색 대상 공개글", testUser, PostStatusEnum.PUBLISHED)
        val myPrivatePost = createPost("검색 대상 내 비공개글", testUser, PostStatusEnum.PRIVATE)
        val myDraftPost = createPost("검색 대상 내 임시저장글", testUser, PostStatusEnum.DRAFT)
        val otherPrivatePost = createPost("검색 대상 타인 비공개글", otherUser, PostStatusEnum.PRIVATE)

        // when
        val matchedIds =
            given()
                .header("Authorization", "Bearer $accessToken")
                .queryParam("keyword", "검색")
                .`when`()
                .get("/open-api/posts/search")
                .then()
                .statusCode(HttpStatus.OK.value())
                .extract()
                .path<List<String>>("data.content.id")

        // then
        assertThat(matchedIds).containsExactlyInAnyOrder(
            publishedPost.id.toString(),
            myPrivatePost.id.toString(),
        )
        assertThat(matchedIds).doesNotContain(
            myDraftPost.id.toString(),
            otherPrivatePost.id.toString(),
        )
    }

    @Test
    @DisplayName("검색 결과가 페이지 크기를 넘으면 커서로 나머지를 중복 없이 조회한다")
    fun searchPosts_paginatesWithCursorWithoutDuplication() {
        // given — 최신순 정렬이므로 나중에 저장한 게시물이 앞선다
        val oldestPost = createPublishedPost(title = "검색 결과 1", content = "본문")
        val middlePost = createPublishedPost(title = "검색 결과 2", content = "본문")
        val newestPost = createPublishedPost(title = "검색 결과 3", content = "본문")

        // when
        val firstPage =
            given()
                .queryParam("keyword", "검색")
                .queryParam("size", 2)
                .`when`()
                .get("/open-api/posts/search")
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
                .get("/open-api/posts/search")
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
    @DisplayName("일치하는 게시물이 없으면 빈 목록을 반환한다")
    fun searchPosts_noMatch_returnsEmptyPage() {
        // given
        createPublishedPost(title = "인덱스 튜닝", content = "본문")

        // when
        val response =
            given()
                .queryParam("keyword", "존재하지않는키워드")
                .`when`()
                .get("/open-api/posts/search")
                .then()
                .statusCode(HttpStatus.OK.value())
                .extract()

        // then
        assertThat(response.path<List<String>>("data.content")).isEmpty()
        assertThat(response.path<Boolean>("data.hasNext")).isFalse()
        assertThat(response.path<String>("data.nextCursor")).isNull()
    }

    @Test
    @DisplayName("tsvector 컬럼과 트리거가 제거된 뒤에도 게시물 저장과 수정이 동작한다")
    fun postWrite_afterTsvectorRemoval_succeeds() {
        // given
        val tsvectorColumnCount =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'posts' AND column_name = 'content_tsvector'",
                Int::class.java,
            )

        // when
        val savedPost = createPublishedPost(title = "제거 후 저장", content = "본문")
        savedPost.title = "제거 후 수정"
        val updatedPost = postRepository.saveAndFlush(savedPost)

        // then
        assertThat(tsvectorColumnCount).isZero()
        assertThat(postRepository.findById(updatedPost.id!!).orElseThrow().title).isEqualTo("제거 후 수정")
    }

    private fun createPublishedPost(
        title: String,
        content: String,
    ): Post =
        postRepository.saveAndFlush(
            Post(
                title = title,
                content = content,
                author = testUser,
                status = PostStatusEnum.PUBLISHED,
            ),
        )

    private fun createPost(
        title: String,
        author: User,
        status: PostStatusEnum,
    ): Post =
        postRepository.saveAndFlush(
            Post(
                title = title,
                content = "본문",
                author = author,
                status = status,
            ),
        )
}
