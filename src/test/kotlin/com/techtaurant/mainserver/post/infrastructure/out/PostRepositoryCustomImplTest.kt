package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.post.dto.PostCursor
import com.techtaurant.mainserver.post.entity.Category
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.entity.PostDailyStats
import com.techtaurant.mainserver.post.entity.PostPeriod
import com.techtaurant.mainserver.post.entity.PostSortType
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.entity.UserBan
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserBanRepository
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

@Transactional
@ActiveProfiles("test")
class PostRepositoryCustomImplTest : IntegrationTest() {
    @Autowired
    private lateinit var postRepository: PostRepository

    @Autowired
    private lateinit var postDailyStatsRepository: PostDailyStatsRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var userBanRepository: UserBanRepository

    @Autowired
    private lateinit var categoryRepository: CategoryRepository

    private lateinit var userA: User
    private lateinit var userB: User
    private lateinit var category: Category

    @BeforeEach
    fun setUpTestData() {
        postDailyStatsRepository.deleteAllInBatch()
        postRepository.deleteAll()
        userBanRepository.deleteAllInBatch()

        userA =
            userRepository.save(
                User(
                    name = "사용자A",
                    email = "a@example.com",
                    provider = OAuthProvider.GOOGLE,
                    identifier = "user-a-${UUID.randomUUID()}",
                    role = UserRole.USER,
                    profileImageUrl = "https://example.com/a.jpg",
                ),
            )
        userB =
            userRepository.save(
                User(
                    name = "사용자B",
                    email = "b@example.com",
                    provider = OAuthProvider.GOOGLE,
                    identifier = "user-b-${UUID.randomUUID()}",
                    role = UserRole.USER,
                    profileImageUrl = "https://example.com/b.jpg",
                ),
            )
        category =
            categoryRepository.save(
                Category(
                    user = userA,
                    name = "테스트 카테고리",
                    path = "테스트카테고리",
                    depth = 1,
                ),
            )
    }

    private fun createPost(
        author: User,
        status: PostStatusEnum = PostStatusEnum.PUBLISHED,
        postCategory: Category? = null,
        viewCount: Long = 0,
        likeCount: Long = 0,
        commentCount: Long = 0,
    ): Post =
        postRepository.save(
            Post(
                title = "게시물",
                content = "내용",
                author = author,
                status = status,
                category = postCategory,
                viewCount = viewCount,
                likeCount = likeCount,
                commentCount = commentCount,
            ),
        )

    private fun movePostCreatedAt(
        post: Post,
        daysAgo: Long,
    ): Post {
        val createdAt = Instant.now().minus(daysAgo, ChronoUnit.DAYS)
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

    @Nested
    @DisplayName("save 카운터 보존")
    inner class SaveCounterPreservation {
        @Test
        @DisplayName("save는 조회 이후 원자적으로 증가한 조회수/추천수/댓글수를 덮어쓰지 않는다")
        fun save_doesNotOverwriteAtomicallyIncrementedCounters() {
            // given
            val postId = createPost(userA).id!!
            val stalePost = postRepository.findById(postId).orElseThrow()

            postRepository.incrementViewCount(postId)
            postRepository.incrementLikeCount(postId)
            postRepository.incrementCommentCount(postId)

            // when
            stalePost.title = "수정된 제목"
            postRepository.save(stalePost)

            // then
            val reloadedPost = postRepository.findById(postId).orElseThrow()
            assertThat(reloadedPost.title).isEqualTo("수정된 제목")
            assertThat(reloadedPost.viewCount).isEqualTo(1L)
            assertThat(reloadedPost.likeCount).isEqualTo(1L)
            assertThat(reloadedPost.commentCount).isEqualTo(1L)
        }
    }

    @Nested
    @DisplayName("authorId 필터링")
    inner class AuthorIdFilter {
        @Test
        @DisplayName("authorId 지정 시 해당 작성자의 게시물만 반환한다")
        fun findPostsWithConditions_withAuthorId_returnsOnlyAuthorPosts() {
            // given
            val postByA = createPost(userA)
            createPost(userB)

            // when
            val result =
                postRepository.findPostsWithConditions(
                    cursor = null,
                    size = 10,
                    period = PostPeriod.ALL,
                    sortType = PostSortType.LATEST,
                    authorId = userA.id!!,
                )

            // then
            assertThat(result).hasSize(1)
            assertThat(result[0].id).isEqualTo(postByA.id)
        }

        @Test
        @DisplayName("authorId가 null이면 모든 작성자의 게시물을 반환한다")
        fun findPostsWithConditions_withoutAuthorId_returnsAllPosts() {
            // given
            createPost(userA)
            createPost(userB)

            // when
            val result =
                postRepository.findPostsWithConditions(
                    cursor = null,
                    size = 10,
                    period = PostPeriod.ALL,
                    sortType = PostSortType.LATEST,
                    authorId = null,
                )

            // then
            assertThat(result).hasSize(2)
        }
    }

    @Nested
    @DisplayName("statuses 필터링")
    inner class StatusesFilter {
        @Test
        @DisplayName("statuses가 null이면 PUBLISHED만 반환한다")
        fun findPostsWithConditions_withoutStatuses_returnsPublishedOnly() {
            // given
            val published = createPost(userA, PostStatusEnum.PUBLISHED)
            createPost(userA, PostStatusEnum.DRAFT)
            createPost(userA, PostStatusEnum.PRIVATE)

            // when
            val result =
                postRepository.findPostsWithConditions(
                    cursor = null,
                    size = 10,
                    period = PostPeriod.ALL,
                    sortType = PostSortType.LATEST,
                    statuses = null,
                )

            // then
            assertThat(result).hasSize(1)
            assertThat(result[0].id).isEqualTo(published.id)
        }

        @Test
        @DisplayName("모든 상태를 지정하면 전체 게시물을 반환한다")
        fun findPostsWithConditions_withAllStatuses_returnsAllPosts() {
            // given
            createPost(userA, PostStatusEnum.PUBLISHED)
            createPost(userA, PostStatusEnum.DRAFT)
            createPost(userA, PostStatusEnum.PRIVATE)

            // when
            val result =
                postRepository.findPostsWithConditions(
                    cursor = null,
                    size = 10,
                    period = PostPeriod.ALL,
                    sortType = PostSortType.LATEST,
                    statuses = PostStatusEnum.entries,
                )

            // then
            assertThat(result).hasSize(3)
        }

        @Test
        @DisplayName("PUBLISHED만 지정하면 PUBLISHED 게시물만 반환한다")
        fun findPostsWithConditions_withPublishedOnly_returnsPublishedPosts() {
            // given
            val published = createPost(userA, PostStatusEnum.PUBLISHED)
            createPost(userA, PostStatusEnum.DRAFT)

            // when
            val result =
                postRepository.findPostsWithConditions(
                    cursor = null,
                    size = 10,
                    period = PostPeriod.ALL,
                    sortType = PostSortType.LATEST,
                    statuses = listOf(PostStatusEnum.PUBLISHED),
                )

            // then
            assertThat(result).hasSize(1)
            assertThat(result[0].id).isEqualTo(published.id)
        }
    }

    @Nested
    @DisplayName("categoryId 필터링")
    inner class CategoryIdFilter {
        @Test
        @DisplayName("categoryId 지정 시 해당 카테고리의 게시물만 반환한다")
        fun findPostsWithConditions_withCategoryId_returnsOnlyCategoryPosts() {
            // given
            val categorizedPost = createPost(userA, postCategory = category)
            createPost(userA)

            // when
            val result =
                postRepository.findPostsWithConditions(
                    cursor = null,
                    size = 10,
                    period = PostPeriod.ALL,
                    sortType = PostSortType.LATEST,
                    categoryId = category.id!!,
                )

            // then
            assertThat(result).hasSize(1)
            assertThat(result[0].id).isEqualTo(categorizedPost.id)
        }

        @Test
        @DisplayName("categoryId가 null이면 모든 카테고리의 게시물을 반환한다")
        fun findPostsWithConditions_withoutCategoryId_returnsAllPosts() {
            // given
            createPost(userA, postCategory = category)
            createPost(userA)

            // when
            val result =
                postRepository.findPostsWithConditions(
                    cursor = null,
                    size = 10,
                    period = PostPeriod.ALL,
                    sortType = PostSortType.LATEST,
                    categoryId = null,
                )

            // then
            assertThat(result).hasSize(2)
        }
    }

    @Nested
    @DisplayName("복합 필터링")
    inner class CombinedFilter {
        @Test
        @DisplayName("authorId + statuses + categoryId를 함께 적용하면 교집합 결과를 반환한다")
        fun findPostsWithConditions_combinedFilters_returnsIntersection() {
            // given
            val target = createPost(userA, PostStatusEnum.PUBLISHED, category)
            createPost(userA, PostStatusEnum.DRAFT, category)
            createPost(userB, PostStatusEnum.PUBLISHED, category)
            createPost(userA, PostStatusEnum.PUBLISHED)

            // when
            val result =
                postRepository.findPostsWithConditions(
                    cursor = null,
                    size = 10,
                    period = PostPeriod.ALL,
                    sortType = PostSortType.LATEST,
                    authorId = userA.id!!,
                    statuses = listOf(PostStatusEnum.PUBLISHED),
                    categoryId = category.id!!,
                )

            // then
            assertThat(result).hasSize(1)
            assertThat(result[0].id).isEqualTo(target.id)
        }
    }

    @Nested
    @DisplayName("기간 통계 랭킹")
    inner class PeriodStatsRanking {
        @Test
        @DisplayName("댓글순 기간 랭킹은 게시물 작성일이 아니라 기간 내 일별 댓글 집계 합계로 정렬하고 필터링한다")
        fun findPostsWithConditions_commentPeriodRanking_usesRecentDailyStatsInsteadOfPostCreatedAt() {
            // given
            val oldPostWithTodayComments = movePostCreatedAt(createPost(userA), daysAgo = 500)
            createDailyStats(oldPostWithTodayComments, daysAgo = 0, commentCount = 5)

            val oldPostWithRecentComments = movePostCreatedAt(createPost(userA), daysAgo = 400)
            createDailyStats(oldPostWithRecentComments, daysAgo = 3, commentCount = 2)

            val recentPostWithoutStats = createPost(userA)
            val oldPostWithStaleComments = movePostCreatedAt(createPost(userA), daysAgo = 300)
            createDailyStats(oldPostWithStaleComments, daysAgo = 31, commentCount = 99)

            // when
            val result =
                postRepository.findPostsWithConditions(
                    cursor = null,
                    size = 10,
                    period = PostPeriod.MONTH,
                    sortType = PostSortType.COMMENT,
                )

            // then
            assertThat(result).extracting("id").containsExactly(
                oldPostWithTodayComments.id,
                oldPostWithRecentComments.id,
            )
            assertThat(result).extracting("id").doesNotContain(
                recentPostWithoutStats.id,
                oldPostWithStaleComments.id,
            )
        }

        @Test
        @DisplayName("조회순 전체 랭킹도 post 누적값이 아니라 일별 집계 전체 합계로 정렬한다")
        fun findPostsWithConditions_viewAllStatsRanking_usesDailyStatsTotalSum() {
            // given
            val postWithMoreDailyStats = movePostCreatedAt(createPost(userA, viewCount = 1), daysAgo = 500)
            createDailyStats(postWithMoreDailyStats, daysAgo = 100, viewCount = 7)

            val postWithFewerDailyStats = movePostCreatedAt(createPost(userA, viewCount = 100), daysAgo = 400)
            createDailyStats(postWithFewerDailyStats, daysAgo = 1, viewCount = 3)

            // when
            val result =
                postRepository.findPostsWithConditions(
                    cursor = null,
                    size = 10,
                    period = PostPeriod.ALL,
                    sortType = PostSortType.VIEW,
                )

            // then
            assertThat(result).extracting("id").containsExactly(
                postWithMoreDailyStats.id,
                postWithFewerDailyStats.id,
            )
        }

        @Test
        @DisplayName("조회순 기간 랭킹은 기간 내 일별 조회 집계 합계로 정렬한다")
        fun findPostsWithConditions_viewPeriodRanking_usesRecentDailyStatsSum() {
            // given
            val oldPostWithMoreViews = movePostCreatedAt(createPost(userA, viewCount = 1), daysAgo = 500)
            createDailyStats(oldPostWithMoreViews, daysAgo = 0, viewCount = 7)

            val oldPostWithFewerViews = movePostCreatedAt(createPost(userA, viewCount = 100), daysAgo = 400)
            createDailyStats(oldPostWithFewerViews, daysAgo = 1, viewCount = 3)

            // when
            val result =
                postRepository.findPostsWithConditions(
                    cursor = null,
                    size = 10,
                    period = PostPeriod.WEEK,
                    sortType = PostSortType.VIEW,
                )

            // then
            assertThat(result).extracting("id").containsExactly(
                oldPostWithMoreViews.id,
                oldPostWithFewerViews.id,
            )
        }

        @Test
        @DisplayName("추천순 기간 랭킹은 기간 내 일별 추천 집계 합계로 정렬한다")
        fun findPostsWithConditions_likePeriodRanking_usesRecentDailyStatsSum() {
            // given
            val oldPostWithMoreLikes = movePostCreatedAt(createPost(userA, likeCount = 1), daysAgo = 500)
            createDailyStats(oldPostWithMoreLikes, daysAgo = 0, likeCount = 4)

            val oldPostWithFewerLikes = movePostCreatedAt(createPost(userA, likeCount = 100), daysAgo = 400)
            createDailyStats(oldPostWithFewerLikes, daysAgo = 1, likeCount = 2)

            // when
            val result =
                postRepository.findPostsWithConditions(
                    cursor = null,
                    size = 10,
                    period = PostPeriod.WEEK,
                    sortType = PostSortType.LIKE,
                )

            // then
            assertThat(result).extracting("id").containsExactly(
                oldPostWithMoreLikes.id,
                oldPostWithFewerLikes.id,
            )
        }

        @Test
        @DisplayName("기간 랭킹 커서는 전체 누적값이 아니라 기간 내 일별 집계 합계로 다음 페이지를 조회한다")
        fun findPostsWithConditions_periodRankingCursor_usesRecentDailyStatsSortValue() {
            // given
            val firstPageLastPost = movePostCreatedAt(createPost(userA, commentCount = 1_000), daysAgo = 500)
            createDailyStats(firstPageLastPost, daysAgo = 0, commentCount = 10)

            val secondPageFirstPost = movePostCreatedAt(createPost(userA, commentCount = 999), daysAgo = 400)
            createDailyStats(secondPageFirstPost, daysAgo = 0, commentCount = 5)

            val secondPageSecondPost = movePostCreatedAt(createPost(userA, commentCount = 998), daysAgo = 300)
            createDailyStats(secondPageSecondPost, daysAgo = 0, commentCount = 1)

            val cursor =
                PostCursor(
                    sortValue = 10,
                    createdAt = firstPageLastPost.createdAt,
                    id = firstPageLastPost.id!!,
                    sortType = PostSortType.COMMENT,
                )

            // when
            val result =
                postRepository.findPostsWithConditions(
                    cursor = cursor,
                    size = 10,
                    period = PostPeriod.MONTH,
                    sortType = PostSortType.COMMENT,
                )

            // then
            assertThat(result).extracting("id").containsExactly(
                secondPageFirstPost.id,
                secondPageSecondPost.id,
            )
        }

        @Test
        @DisplayName("최신순 기간 필터는 기존처럼 게시물 작성일 기준으로 동작한다")
        fun findPostsWithConditions_latestPeriod_keepsPostCreatedAtFilter() {
            // given
            val recentPost = createPost(userA)
            val oldPost = movePostCreatedAt(createPost(userA), daysAgo = 500)
            createDailyStats(oldPost, daysAgo = 0, commentCount = 5)

            // when
            val result =
                postRepository.findPostsWithConditions(
                    cursor = null,
                    size = 10,
                    period = PostPeriod.MONTH,
                    sortType = PostSortType.LATEST,
                )

            // then
            assertThat(result).extracting("id").contains(recentPost.id)
            assertThat(result).extracting("id").doesNotContain(oldPost.id)
        }
    }

    @Nested
    @DisplayName("visibleToUserId 필터링")
    inner class VisibleToUserIdFilter {
        @Test
        @DisplayName("visibleToUserId 지정 시 PUBLISHED + 해당 사용자의 PRIVATE 게시물을 반환한다")
        fun findPostsWithConditions_withVisibleToUserId_returnsPublishedAndOwnPosts() {
            // given
            val publishedByA = createPost(userA, PostStatusEnum.PUBLISHED)
            val draftByA = createPost(userA, PostStatusEnum.DRAFT)
            val privateByA = createPost(userA, PostStatusEnum.PRIVATE)
            val publishedByB = createPost(userB, PostStatusEnum.PUBLISHED)
            createPost(userB, PostStatusEnum.DRAFT)
            createPost(userB, PostStatusEnum.PRIVATE)

            // when
            val result =
                postRepository.findPostsWithConditions(
                    cursor = null,
                    size = 10,
                    period = PostPeriod.ALL,
                    sortType = PostSortType.LATEST,
                    visibleToUserId = userA.id!!,
                )

            // then
            val resultIds = result.map { it.id }.toSet()
            assertThat(resultIds).containsExactlyInAnyOrder(
                publishedByA.id,
                privateByA.id,
                publishedByB.id,
            )
            assertThat(resultIds).doesNotContain(draftByA.id)
        }

        @Test
        @DisplayName("visibleToUserId가 null이면 PUBLISHED만 반환한다")
        fun findPostsWithConditions_withoutVisibleToUserId_returnsPublishedOnly() {
            // given
            val publishedByA = createPost(userA, PostStatusEnum.PUBLISHED)
            createPost(userA, PostStatusEnum.DRAFT)
            createPost(userA, PostStatusEnum.PRIVATE)
            val publishedByB = createPost(userB, PostStatusEnum.PUBLISHED)
            createPost(userB, PostStatusEnum.DRAFT)

            // when
            val result =
                postRepository.findPostsWithConditions(
                    cursor = null,
                    size = 10,
                    period = PostPeriod.ALL,
                    sortType = PostSortType.LATEST,
                    visibleToUserId = null,
                )

            // then
            val resultIds = result.map { it.id }.toSet()
            assertThat(resultIds).containsExactlyInAnyOrder(
                publishedByA.id,
                publishedByB.id,
            )
        }

        @Test
        @DisplayName("visibleToUserId는 statuses보다 우선 적용된다")
        fun findPostsWithConditions_visibleToUserIdOverridesStatuses() {
            // given
            val publishedByA = createPost(userA, PostStatusEnum.PUBLISHED)
            val draftByA = createPost(userA, PostStatusEnum.DRAFT)
            val privateByA = createPost(userA, PostStatusEnum.PRIVATE)
            val publishedByB = createPost(userB, PostStatusEnum.PUBLISHED)
            createPost(userB, PostStatusEnum.DRAFT)

            // when
            val result =
                postRepository.findPostsWithConditions(
                    cursor = null,
                    size = 10,
                    period = PostPeriod.ALL,
                    sortType = PostSortType.LATEST,
                    statuses = listOf(PostStatusEnum.PUBLISHED),
                    visibleToUserId = userA.id!!,
                )

            // then
            val resultIds = result.map { it.id }.toSet()
            assertThat(resultIds).containsExactlyInAnyOrder(
                publishedByA.id,
                privateByA.id,
                publishedByB.id,
            )
            assertThat(resultIds).doesNotContain(draftByA.id)
        }

        @Test
        @DisplayName("viewerId 지정 시 차단한 작성자의 게시물은 제외한다")
        fun findPostsWithConditions_withViewerId_excludesBannedAuthorPosts() {
            // given
            val visiblePost = createPost(userA, PostStatusEnum.PUBLISHED)
            createPost(userB, PostStatusEnum.PUBLISHED)
            userBanRepository.save(UserBan(user = userA, bannedUser = userB))

            // when
            val result =
                postRepository.findPostsWithConditions(
                    cursor = null,
                    size = 10,
                    period = PostPeriod.ALL,
                    sortType = PostSortType.LATEST,
                    visibleToUserId = userA.id!!,
                    viewerId = userA.id!!,
                )

            // then
            assertThat(result).extracting("id").containsExactly(visiblePost.id)
        }
    }
}
