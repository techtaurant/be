package com.techtaurant.mainserver.post.infrastructure.`in`

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.dto.CursorPageResponse
import com.techtaurant.mainserver.post.dto.PostListItemResponse
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.entity.PostDailyStats
import com.techtaurant.mainserver.post.entity.Tag
import com.techtaurant.mainserver.post.infrastructure.out.PostDailyStatsRepository
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.post.infrastructure.out.TagRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.restassured.RestAssured
import io.restassured.common.mapper.TypeRef
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Calendar
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisplayName("게시물 목록 조회 API")
class PostControllerTest : IntegrationTest() {
    @Autowired
    private lateinit var postRepository: PostRepository

    @Autowired
    private lateinit var postDailyStatsRepository: PostDailyStatsRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var tagRepository: TagRepository

    private lateinit var testUser: User
    private lateinit var testPosts: List<Post>

    @BeforeEach
    fun setup() {
        postDailyStatsRepository.deleteAllInBatch()
        postRepository.deleteAllInBatch()
        userRepository.deleteAllInBatch()

        // Given: 테스트 사용자 생성
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

        // Given: 다양한 통계를 가진 테스트 게시물 생성
        testPosts = createTestPosts()
    }

    @Nested
    @DisplayName("정렬 기준별 게시물 로딩 검증")
    inner class SortTypeTest {
        @Test
        @DisplayName("LATEST - 최신순으로 정렬된 게시물이 로딩되어야 한다")
        fun whenSortByLatest_thenPostsOrderedByCreatedAtDesc() {
            // When: 최신순 조회
            val response =
                RestAssured
                    .given()
                    .queryParam("sort", "LATEST")
                    .queryParam("size", 10)
                    .`when`()
                    .get("/open-api/posts")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<PostListItemResponse>>>() {})

            // Then: 최신 게시물부터 순서대로 정렬되어 있어야 함
            val content = response.data!!.content
            assertEquals(3, content.size)

            // 가장 최신 게시물이 먼저 와야 함
            assertEquals(testPosts[2].id, content[0].id, "가장 최신 게시물이 첫 번째여야 함")
            assertEquals(testPosts[1].id, content[1].id)
            assertEquals(testPosts[0].id, content[2].id, "가장 오래된 게시물이 마지막이어야 함")
        }

        @Test
        @DisplayName("VIEW - 조회수 기준으로 정렬된 게시물이 로딩되어야 한다")
        fun whenSortByView_thenPostsOrderedByViewCountDesc() {
            // When: 조회수 기준 정렬 조회
            val response =
                RestAssured
                    .given()
                    .queryParam("sort", "VIEW")
                    .queryParam("size", 10)
                    .`when`()
                    .get("/open-api/posts")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<PostListItemResponse>>>() {})

            // Then: 조회수가 높은 순서대로 정렬되어 있어야 함
            val content = response.data!!.content
            assertEquals(3, content.size)

            // 조회수가 100인 게시물이 가장 먼저 와야 함
            assertEquals(100L, content[0].viewCount, "조회수 100인 게시물이 첫 번째여야 함")
            assertEquals(50L, content[1].viewCount)
            assertEquals(10L, content[2].viewCount)

            // 조회수가 같은 경우 최신순으로 정렬되어야 함
            assertTrue(content[0].viewCount >= content[1].viewCount)
            assertTrue(content[1].viewCount >= content[2].viewCount)
        }

        @Test
        @DisplayName("LIKE - 좋아요수 기준으로 정렬된 게시물이 로딩되어야 한다")
        fun whenSortByLike_thenPostsOrderedByLikeCountDesc() {
            // When: 좋아요수 기준 정렬 조회
            val response =
                RestAssured
                    .given()
                    .queryParam("sort", "LIKE")
                    .queryParam("size", 10)
                    .`when`()
                    .get("/open-api/posts")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<PostListItemResponse>>>() {})

            // Then: 좋아요수가 높은 순서대로 정렬되어 있어야 함
            val content = response.data!!.content
            assertEquals(3, content.size)

            // 좋아요수가 내림차순으로 정렬되어야 함
            assertTrue(content[0].likeCount >= content[1].likeCount)
            assertTrue(content[1].likeCount >= content[2].likeCount)
        }

        @Test
        @DisplayName("COMMENT - 댓글수 기준으로 정렬된 게시물이 로딩되어야 한다")
        fun whenSortByComment_thenPostsOrderedByCommentCountDesc() {
            // When: 댓글수 기준 정렬 조회
            val response =
                RestAssured
                    .given()
                    .queryParam("sort", "COMMENT")
                    .queryParam("size", 10)
                    .`when`()
                    .get("/open-api/posts")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<PostListItemResponse>>>() {})

            // Then: 댓글수가 높은 순서대로 정렬되어 있어야 함
            val content = response.data!!.content
            assertEquals(3, content.size)

            // 댓글수가 내림차순으로 정렬되어야 함
            assertTrue(content[0].commentCount >= content[1].commentCount)
            assertTrue(content[1].commentCount >= content[2].commentCount)
        }
    }

    @Nested
    @DisplayName("기간 필터별 게시물 로딩 검증")
    inner class PeriodFilterTest {
        @Test
        @DisplayName("WEEK - 최근 7일 내의 게시물만 로딩되어야 한다")
        fun whenPeriodIsWeek_thenOnlyLastSevenDaysPostsLoaded() {
            // When: 1주일 기간 필터로 조회
            val response =
                RestAssured
                    .given()
                    .queryParam("period", "WEEK")
                    .queryParam("size", 10)
                    .`when`()
                    .get("/open-api/posts")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<PostListItemResponse>>>() {})

            // Then: 최근 7일 이내의 게시물만 로드되어야 함
            val content = response.data!!.content
            assertEquals(2, content.size, "최근 7일 내 게시물은 2개여야 함")
        }

        @Test
        @DisplayName("MONTH - 최근 30일 내의 게시물만 로딩되어야 한다")
        fun whenPeriodIsMonth_thenOnlyLastThirtyDaysPostsLoaded() {
            // When: 1개월 기간 필터로 조회
            val response =
                RestAssured
                    .given()
                    .queryParam("period", "MONTH")
                    .queryParam("size", 10)
                    .`when`()
                    .get("/open-api/posts")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<PostListItemResponse>>>() {})

            // Then: 최근 30일 이내의 게시물만 로드되어야 함
            val content = response.data!!.content
            assertEquals(2, content.size, "최근 30일 내 게시물은 2개여야 함")
        }

        @Test
        @DisplayName("YEAR - 최근 365일 내의 게시물만 로딩되어야 한다")
        fun whenPeriodIsYear_thenOnlyLastYearPostsLoaded() {
            // When: 1년 기간 필터로 조회
            val response =
                RestAssured
                    .given()
                    .queryParam("period", "YEAR")
                    .queryParam("size", 10)
                    .`when`()
                    .get("/open-api/posts")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<PostListItemResponse>>>() {})

            // Then: 최근 365일 이내의 모든 게시물이 로드되어야 함
            val content = response.data!!.content
            assertEquals(3, content.size, "최근 365일 내 게시물은 3개여야 함")
        }

        @Test
        @DisplayName("ALL - 모든 게시물이 로딩되어야 한다")
        fun whenPeriodIsAll_thenAllPostsLoaded() {
            // When: 기간 필터 없이 조회 (기본값 ALL)
            val response =
                RestAssured
                    .given()
                    .queryParam("size", 10)
                    .`when`()
                    .get("/open-api/posts")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<PostListItemResponse>>>() {})

            // Then: 모든 게시물이 로드되어야 함
            val content = response.data!!.content
            assertEquals(3, content.size, "총 3개의 게시물이 로드되어야 함")
        }
    }

    @Nested
    @DisplayName("기본 게시물 조회 검증")
    inner class BasicPostLoadingTest {
        @Test
        @DisplayName("게시물 기본 정보가 올바르게 로딩되어야 한다")
        fun whenGetPosts_thenPostDataLoadedCorrectly() {
            // When: 게시물 목록 조회
            val response =
                RestAssured
                    .given()
                    .queryParam("size", 10)
                    .`when`()
                    .get("/open-api/posts")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<PostListItemResponse>>>() {})

            // Then: 게시물의 기본 정보가 올바르게 로드되어야 함
            val content = response.data!!.content
            val firstPost = content[0]

            assertNotNull(firstPost.id)
            assertNotNull(firstPost.title)
            assertNotNull(firstPost.authorName)
            assertNotNull(firstPost.createdAt)
            assertEquals("Test User", firstPost.authorName, "작성자 이름이 올바르게 로드되어야 함")
        }

        @Test
        @DisplayName("태그 정보가 올바르게 로딩되어야 한다")
        fun whenGetPosts_thenTagDataLoadedCorrectly() {
            // When: 게시물 목록 조회
            val response =
                RestAssured
                    .given()
                    .queryParam("size", 10)
                    .`when`()
                    .get("/open-api/posts")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<PostListItemResponse>>>() {})

            // Then: 태그 정보가 올바르게 로드되어야 함
            val content = response.data!!.content
            val firstPost = content[0]

            assertNotNull(firstPost.tags)
            assertTrue(firstPost.tags.isNotEmpty(), "태그가 로드되어야 함")
            assertTrue(firstPost.tags.all { it.id != null }, "태그 ID가 모두 로드되어야 함")
            assertTrue(firstPost.tags.all { it.name.isNotBlank() }, "태그 이름이 모두 로드되어야 함")
        }

        @Test
        @DisplayName("통계 정보(조회수, 좋아요, 댓글)가 올바르게 로딩되어야 한다")
        fun whenGetPosts_thenStatsDataLoadedCorrectly() {
            // When: 게시물 목록 조회
            val response =
                RestAssured
                    .given()
                    .queryParam("size", 10)
                    .`when`()
                    .get("/open-api/posts")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<PostListItemResponse>>>() {})

            // Then: 통계 정보가 올바르게 로드되어야 함
            val content = response.data!!.content
            content.forEach { post ->
                assertTrue(post.viewCount >= 0, "조회수는 음수가 아니어야 함")
                assertTrue(post.likeCount >= 0, "좋아요수는 음수가 아니어야 함")
                assertTrue(post.commentCount >= 0, "댓글수는 음수가 아니어야 함")
            }
        }

        @Test
        @DisplayName("게시물 내용이 최대 2000자로 함께 로딩되어야 한다")
        fun whenGetPosts_thenContentLoadedWithMaxLength2000() {
            // Given - 길이가 다른 게시물 본문이 준비되어 있음

            // When - 게시물 목록 조회
            val response =
                RestAssured
                    .given()
                    .queryParam("size", 10)
                    .`when`()
                    .get("/open-api/posts")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<PostListItemResponse>>>() {})

            // Then - 짧은 본문은 원문 그대로, 긴 본문은 2000자로 잘려야 함
            val contentById = response.data!!.content.associateBy { it.id }
            val shortContentPost = contentById[testPosts[1].id]
            val longContentPost = contentById[testPosts[2].id]

            assertNotNull(shortContentPost)
            assertEquals("짧은 본문", shortContentPost.content)

            assertNotNull(longContentPost)
            assertEquals(2000, longContentPost.content.length)
            assertEquals("가".repeat(2000), longContentPost.content)
        }
    }

    @Nested
    @DisplayName("페이지네이션 검증")
    inner class PaginationTest {
        @Test
        @DisplayName("첫 페이지 조회 시 nextCursor가 반환되어야 한다")
        fun whenGetFirstPage_thenNextCursorReturned() {
            // When: 첫 페이지 조회 (size=1로 다음 페이지가 있도록 설정)
            val response =
                RestAssured
                    .given()
                    .queryParam("size", 1)
                    .`when`()
                    .get("/open-api/posts")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<PostListItemResponse>>>() {})

            // Then: 다음 페이지가 있으면 nextCursor가 반환되어야 함
            assertTrue(response.data!!.hasNext, "다음 페이지가 있어야 함")
            assertNotNull(response.data!!.nextCursor, "nextCursor가 반환되어야 함")
            assertEquals(1, response.data!!.size)
        }

        @Test
        @DisplayName("마지막 페이지 조회 시 nextCursor가 null이어야 한다")
        fun whenGetLastPage_thenNextCursorIsNull() {
            // When: 충분한 크기로 모든 데이터를 한 번에 조회
            val response =
                RestAssured
                    .given()
                    .queryParam("size", 100)
                    .`when`()
                    .get("/open-api/posts")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<PostListItemResponse>>>() {})

            // Then: 마지막 페이지이므로 nextCursor가 null이어야 함
            assertTrue(!response.data!!.hasNext, "다음 페이지가 없어야 함")
            assertEquals(null, response.data!!.nextCursor, "nextCursor가 null이어야 함")
            assertEquals(3, response.data!!.size)
        }

        @Test
        @DisplayName("커서를 이용한 다음 페이지 조회가 정확해야 한다")
        fun whenGetPostsWithCursor_thenNextPageLoaded() {
            // When: 첫 페이지 조회
            val firstPageResponse =
                RestAssured
                    .given()
                    .queryParam("size", 1)
                    .`when`()
                    .get("/open-api/posts")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<PostListItemResponse>>>() {})

            val firstPagePostId = firstPageResponse.data!!.content[0].id
            val cursor = firstPageResponse.data!!.nextCursor

            // When: 커서를 이용해 다음 페이지 조회
            val secondPageResponse =
                RestAssured
                    .given()
                    .queryParam("cursor", cursor)
                    .queryParam("size", 1)
                    .`when`()
                    .get("/open-api/posts")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<PostListItemResponse>>>() {})

            // Then: 다음 페이지의 게시물이 첫 번째와 달라야 함
            val secondPagePostId = secondPageResponse.data!!.content[0].id
            assertEquals(1, secondPageResponse.data!!.size)
            assertTrue(firstPagePostId != secondPagePostId, "다음 페이지의 게시물이 달라야 함")
        }
    }

    @Nested
    @DisplayName("필터 조합 검증")
    inner class CombinedFilterTest {
        @Test
        @DisplayName("태그 ID를 여러 개 전달하면 OR 조건으로 게시물이 조회되어야 한다")
        fun whenFilterByMultipleTagIds_thenPostsMatchingAnyTagLoaded() {
            // Given: 각 게시물은 서로 다른 태그를 가진다
            val firstTagId = testPosts[0].tags.first().id
            val thirdTagId = testPosts[2].tags.first().id

            // When: 두 태그 ID를 함께 전달해 목록 조회
            val response =
                RestAssured
                    .given()
                    .queryParam("tagIds", firstTagId, thirdTagId)
                    .queryParam("size", 10)
                    .`when`()
                    .get("/open-api/posts")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<PostListItemResponse>>>() {})

            // Then: 두 태그 중 하나라도 가진 게시물만 반환되어야 함
            val returnedIds = response.data!!.content.map { it.id }.toSet()
            assertEquals(setOf(testPosts[0].id, testPosts[2].id), returnedIds)
            assertTrue(response.data!!.content.none { it.id == testPosts[1].id })
        }

        @Test
        @DisplayName("기간 필터와 정렬을 함께 적용하면 올바르게 로딩되어야 한다")
        fun whenApplyBothPeriodAndSort_thenCorrectPostsLoaded() {
            // When: 최근 7일 내 일별 집계가 있는 게시물을 조회수 기준으로 정렬해서 조회
            val response =
                RestAssured
                    .given()
                    .queryParam("period", "WEEK")
                    .queryParam("sort", "VIEW")
                    .queryParam("size", 10)
                    .`when`()
                    .get("/open-api/posts")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<PostListItemResponse>>>() {})

            // Then: 게시물 작성일이 아니라 최근 일별 집계 데이터를 기준으로 필터링/정렬되어야 함
            val content = response.data!!.content
            assertEquals(3, content.size, "최근 7일 내 일별 집계가 있는 게시물은 3개여야 함")
            assertTrue(
                content[0].viewCount >= content[1].viewCount,
                "조회수 기준 내림차순 정렬되어야 함",
            )
        }
    }

    /**
     * 테스트용 게시물 생성 헬퍼 메서드
     *
     * 3개의 게시물을 생성하되, 각각 다른 통계값과 생성 시간을 가짐
     * - 게시물 1: 10일 전, 조회수 10, 좋아요 5, 댓글 2
     * - 게시물 2: 5일 전, 조회수 50, 좋아요 25, 댓글 10
     * - 게시물 3: 1일 전, 조회수 100, 좋아요 50, 댓글 20
     */
    private fun createTestPosts(): List<Post> {
        // 테스트용 태그 생성 (UUID로 고유성 보장)
        val tag1 = tagRepository.save(Tag(name = "kotlin-${UUID.randomUUID()}"))
        val tag2 = tagRepository.save(Tag(name = "spring-${UUID.randomUUID()}"))
        val tag3 = tagRepository.save(Tag(name = "test-${UUID.randomUUID()}"))

        // 게시물 1: 50일 전 (YEAR 범위 내, WEEK/MONTH 범위 밖)
        val post1CreatedAt =
            Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -50)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.toInstant()
        val post1 =
            Post(
                title = "Post 1 - 50 days ago",
                content = "Content 1",
                author = testUser,
                viewCount = 10,
                likeCount = 5,
                commentCount = 2,
            ).apply {
                createdAt = post1CreatedAt
            }

        // 게시물 2: 5일 전 (WEEK/MONTH/YEAR 범위 내)
        val post2CreatedAt =
            Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -5)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.toInstant()
        val post2 =
            Post(
                title = "Post 2 - 5 days ago",
                content = "짧은 본문",
                author = testUser,
                viewCount = 50,
                likeCount = 25,
                commentCount = 10,
            ).apply {
                createdAt = post2CreatedAt
            }

        // 게시물 3: 1일 전 (WEEK/MONTH/YEAR 범위 내)
        val post3CreatedAt =
            Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.toInstant()
        val post3 =
            Post(
                title = "Post 3 - 1 day ago",
                content = "가".repeat(2105),
                author = testUser,
                viewCount = 100,
                likeCount = 50,
                commentCount = 20,
            ).apply {
                createdAt = post3CreatedAt
            }

        val savedPosts = postRepository.saveAll(listOf(post1, post2, post3))

        // 저장 후 태그 추가
        savedPosts[0].tags.add(tag1)
        savedPosts[1].tags.add(tag2)
        savedPosts[2].tags.add(tag3)

        val savedPostsWithTags = postRepository.saveAll(savedPosts)
        val today = LocalDate.now(ZoneOffset.UTC)
        postDailyStatsRepository.saveAll(
            savedPostsWithTags.map { post ->
                PostDailyStats(
                    post = post,
                    statDate = today,
                    viewCount = post.viewCount,
                    likeCount = post.likeCount,
                    commentCount = post.commentCount,
                )
            },
        )

        return savedPostsWithTags
    }
}
