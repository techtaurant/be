package com.techtaurant.mainserver.post.infrastructure.`in`

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.entity.PostDailyStats
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.post.infrastructure.out.PostDailyStatsRepository
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
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

@DisplayName("PostReadOpenApiController 통합 테스트")
class PostReadOpenApiControllerIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var postRepository: PostRepository

    @Autowired
    private lateinit var postDailyStatsRepository: PostDailyStatsRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    private lateinit var userBanRepository: UserBanRepository

    private lateinit var testUser: User
    private lateinit var otherUser: User
    private lateinit var accessToken: String

    @BeforeEach
    fun setUpTestData() {
        userBanRepository.deleteAllInBatch()
        postDailyStatsRepository.deleteAllInBatch()
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
    @DisplayName("로그인 사용자가 게시물 목록 조회 시 본인의 PRIVATE 게시물이 포함된다")
    fun getPosts_loggedInUser_includesOwnPrivatePost() {
        // given
        val myPrivatePost =
            postRepository.save(
                Post(
                    title = "내 비공개 게시물",
                    content = "비공개 내용",
                    author = testUser,
                    status = PostStatusEnum.PRIVATE,
                ),
            )
        val myPublishedPost =
            postRepository.save(
                Post(
                    title = "내 공개 게시물",
                    content = "공개 내용",
                    author = testUser,
                    status = PostStatusEnum.PUBLISHED,
                ),
            )
        val otherPrivatePost =
            postRepository.save(
                Post(
                    title = "타인 비공개 게시물",
                    content = "타인 비공개 내용",
                    author = otherUser,
                    status = PostStatusEnum.PRIVATE,
                ),
            )

        // when & then
        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .`when`()
            .get("/open-api/posts")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.content.id", hasItem(myPrivatePost.id.toString()))
            .body("data.content.id", hasItem(myPublishedPost.id.toString()))
            .body("data.content.id", not(hasItem(otherPrivatePost.id.toString())))
    }

    @Test
    @DisplayName("로그인 사용자가 게시물 목록 조회 시 본인의 DRAFT 게시물은 포함되지 않는다")
    fun getPosts_loggedInUser_excludesOwnDraftPost() {
        // given
        val myDraftPost =
            postRepository.save(
                Post(
                    title = "내 임시 저장 게시물",
                    content = "임시 저장 내용",
                    author = testUser,
                    status = PostStatusEnum.DRAFT,
                ),
            )
        val myPrivatePost =
            postRepository.save(
                Post(
                    title = "내 비공개 게시물",
                    content = "비공개 내용",
                    author = testUser,
                    status = PostStatusEnum.PRIVATE,
                ),
            )
        val myPublishedPost =
            postRepository.save(
                Post(
                    title = "내 공개 게시물",
                    content = "공개 내용",
                    author = testUser,
                    status = PostStatusEnum.PUBLISHED,
                ),
            )

        // when & then
        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .`when`()
            .get("/open-api/posts")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.content.id", not(hasItem(myDraftPost.id.toString())))
            .body("data.content.id", hasItem(myPrivatePost.id.toString()))
            .body("data.content.id", hasItem(myPublishedPost.id.toString()))
    }

    @Test
    @DisplayName("로그인 사용자가 차단한 작성자의 게시물은 목록에서 제외된다")
    fun getPosts_excludesBannedAuthorPosts() {
        // given
        val visiblePost =
            postRepository.save(
                Post(
                    title = "보이는 게시물",
                    content = "보이는 내용",
                    author = testUser,
                    status = PostStatusEnum.PUBLISHED,
                ),
            )
        val bannedPost =
            postRepository.save(
                Post(
                    title = "숨겨질 게시물",
                    content = "숨겨질 내용",
                    author = otherUser,
                    status = PostStatusEnum.PUBLISHED,
                ),
            )
        userBanRepository.save(UserBan(user = testUser, bannedUser = otherUser))

        // when & then
        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .`when`()
            .get("/open-api/posts")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.content.id", hasItem(visiblePost.id.toString()))
            .body("data.content.id", not(hasItem(bannedPost.id.toString())))
    }

    @Test
    @DisplayName("댓글순 30일 랭킹은 게시물 작성일이 아니라 기간 내 일별 댓글 집계 합계로 정렬하고 필터링한다")
    fun getPosts_monthCommentRanking_usesRecentDailyStatsInsteadOfPostCreatedAt() {
        // given
        val oldPostWithMoreRecentComments =
            createPublishedPost(
                title = "오래됐지만 최근 댓글이 많은 게시물",
                daysAgo = 500,
                commentCount = 1,
            )
        createDailyStats(oldPostWithMoreRecentComments, daysAgo = 0, commentCount = 6)
        createDailyStats(oldPostWithMoreRecentComments, daysAgo = 3, commentCount = 4)

        val oldPostWithFewerRecentComments =
            createPublishedPost(
                title = "오래됐지만 최근 댓글이 있는 게시물",
                daysAgo = 400,
                commentCount = 999,
            )
        createDailyStats(oldPostWithFewerRecentComments, daysAgo = 2, commentCount = 5)

        val recentPostWithoutStats =
            createPublishedPost(
                title = "최근 작성됐지만 집계가 없는 게시물",
                daysAgo = 1,
                commentCount = 2_000,
            )
        val oldPostWithStaleStats =
            createPublishedPost(
                title = "오래된 집계만 있는 게시물",
                daysAgo = 300,
                commentCount = 3_000,
            )
        createDailyStats(oldPostWithStaleStats, daysAgo = 31, commentCount = 100)

        // when & then
        given()
            .queryParam("period", "MONTH")
            .queryParam("sort", "COMMENT")
            .queryParam("size", 10)
            .`when`()
            .get("/open-api/posts")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body(
                "data.content.id",
                contains(
                    oldPostWithMoreRecentComments.id.toString(),
                    oldPostWithFewerRecentComments.id.toString(),
                ),
            )
            .body("data.content.id", not(hasItem(recentPostWithoutStats.id.toString())))
            .body("data.content.id", not(hasItem(oldPostWithStaleStats.id.toString())))
    }

    @Test
    @DisplayName("조회순 전체 랭킹은 post 누적 조회수가 아니라 일별 집계 전체 합계로 정렬한다")
    fun getPosts_allViewRanking_usesDailyStatsTotalSum() {
        // given
        val postWithMoreDailyStats =
            createPublishedPost(
                title = "누적 조회수는 낮지만 전체 집계 조회수가 높은 게시물",
                daysAgo = 500,
                viewCount = 1,
            )
        createDailyStats(postWithMoreDailyStats, daysAgo = 100, viewCount = 7)

        val postWithFewerDailyStats =
            createPublishedPost(
                title = "누적 조회수는 높지만 전체 집계 조회수가 낮은 게시물",
                daysAgo = 400,
                viewCount = 100,
            )
        createDailyStats(postWithFewerDailyStats, daysAgo = 1, viewCount = 3)

        // when & then
        given()
            .queryParam("period", "ALL")
            .queryParam("sort", "VIEW")
            .queryParam("size", 10)
            .`when`()
            .get("/open-api/posts")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body(
                "data.content.id",
                contains(
                    postWithMoreDailyStats.id.toString(),
                    postWithFewerDailyStats.id.toString(),
                ),
            )
    }

    @Test
    @DisplayName("댓글순 30일 랭킹 커서는 전체 누적 댓글수가 아니라 기간 내 일별 댓글 집계 합계로 다음 페이지를 조회한다")
    fun getPosts_monthCommentRankingCursor_usesRecentDailyStatsSortValue() {
        // given
        val firstPost =
            createPublishedPost(
                title = "첫 페이지 게시물",
                daysAgo = 500,
                commentCount = 1,
            )
        createDailyStats(firstPost, daysAgo = 0, commentCount = 10)

        val secondPost =
            createPublishedPost(
                title = "두 번째 페이지 첫 게시물",
                daysAgo = 400,
                commentCount = 999,
            )
        createDailyStats(secondPost, daysAgo = 0, commentCount = 5)

        val thirdPost =
            createPublishedPost(
                title = "두 번째 페이지 둘째 게시물",
                daysAgo = 300,
                commentCount = 998,
            )
        createDailyStats(thirdPost, daysAgo = 0, commentCount = 1)

        // when
        val firstPageResponse =
            given()
                .queryParam("period", "MONTH")
                .queryParam("sort", "COMMENT")
                .queryParam("size", 1)
                .`when`()
                .get("/open-api/posts")
                .then()
                .statusCode(HttpStatus.OK.value())
                .extract()

        val cursor = firstPageResponse.path<String>("data.nextCursor")

        val secondPageResponse =
            given()
                .queryParam("period", "MONTH")
                .queryParam("sort", "COMMENT")
                .queryParam("size", 2)
                .queryParam("cursor", cursor)
                .`when`()
                .get("/open-api/posts")
                .then()
                .statusCode(HttpStatus.OK.value())
                .extract()

        // then
        assertThat(firstPageResponse.path<List<String>>("data.content.id")).containsExactly(firstPost.id.toString())
        assertThat(cursor).isNotBlank()
        assertThat(secondPageResponse.path<List<String>>("data.content.id")).containsExactly(
            secondPost.id.toString(),
            thirdPost.id.toString(),
        )
    }

    @Test
    @DisplayName("로그인 사용자가 차단한 작성자의 게시물 상세 조회 시 404를 반환한다")
    fun getPostDetail_bannedAuthor_returnsNotFound() {
        // given
        val bannedPost =
            postRepository.save(
                Post(
                    title = "차단된 사용자의 게시물",
                    content = "숨겨질 내용",
                    author = otherUser,
                    status = PostStatusEnum.PUBLISHED,
                ),
            )
        userBanRepository.save(UserBan(user = testUser, bannedUser = otherUser))

        // when & then
        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .`when`()
            .get("/open-api/posts/${bannedPost.id}")
            .then()
            .statusCode(HttpStatus.NOT_FOUND.value())
    }

    @Test
    @DisplayName("게시물 상세 조회는 조회수를 증가시키지 않는다")
    fun getPostDetail_doesNotIncrementViewCount() {
        // given
        val post =
            postRepository.save(
                Post(
                    title = "조회수 분리 게시물",
                    content = "조회 로그 기록은 view-logs API가 담당한다",
                    author = testUser,
                    viewCount = 0,
                    status = PostStatusEnum.PUBLISHED,
                ),
            )

        // when & then
        given()
            .`when`()
            .get("/open-api/posts/${post.id}")
            .then()
            .statusCode(HttpStatus.OK.value())

        val updatedPost = postRepository.findById(post.id!!).orElseThrow()
        assertThat(updatedPost.viewCount).isEqualTo(0)
    }

    private fun createPublishedPost(
        title: String,
        daysAgo: Long,
        viewCount: Long = 0,
        likeCount: Long = 0,
        commentCount: Long = 0,
    ): Post {
        val createdAt = Instant.now().minus(daysAgo, ChronoUnit.DAYS)
        val post =
            postRepository.saveAndFlush(
                Post(
                    title = title,
                    content = "본문",
                    author = testUser,
                    viewCount = viewCount,
                    likeCount = likeCount,
                    commentCount = commentCount,
                    status = PostStatusEnum.PUBLISHED,
                ),
            )

        post.createdAt = createdAt
        post.updatedAt = createdAt
        return postRepository.saveAndFlush(post)
    }

    private fun createDailyStats(
        post: Post,
        daysAgo: Long,
        viewCount: Long = 0,
        likeCount: Long = 0,
        commentCount: Long = 0,
    ): PostDailyStats =
        postDailyStatsRepository.save(
            PostDailyStats(
                post = post,
                statDate = statDateDaysAgo(daysAgo),
                viewCount = viewCount,
                likeCount = likeCount,
                commentCount = commentCount,
            ),
        )

    private fun statDateDaysAgo(daysAgo: Long): LocalDate = LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo)
}
