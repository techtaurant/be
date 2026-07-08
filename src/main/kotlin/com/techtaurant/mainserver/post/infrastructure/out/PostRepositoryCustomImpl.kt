package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.common.base.EntityBase_
import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.post.application.PostWithSortValue
import com.techtaurant.mainserver.post.dto.PostCursor
import com.techtaurant.mainserver.post.entity.Category
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.entity.PostDailyStats
import com.techtaurant.mainserver.post.entity.PostDailyStats_
import com.techtaurant.mainserver.post.entity.PostPeriod
import com.techtaurant.mainserver.post.entity.PostSortType
import com.techtaurant.mainserver.post.entity.Post_
import com.techtaurant.mainserver.post.entity.Tag
import com.techtaurant.mainserver.post.enums.PostStatus
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.entity.UserBan
import com.techtaurant.mainserver.user.entity.UserBan_
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.Tuple
import jakarta.persistence.criteria.CommonAbstractCriteria
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Expression
import jakarta.persistence.criteria.JoinType
import jakarta.persistence.criteria.Path
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 게시물 동적 쿼리 구현체
 *
 * JPA Criteria API와 Metamodel을 사용하여 기간 필터링, 정렬, 커서 기반 페이지네이션을 지원
 */
@Repository
class PostRepositoryCustomImpl : PostRepositoryCustom {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    private data class RankedPostId(
        val postId: UUID,
        val sortValue: Long,
    )

    /**
     * 기간 필터 + 정렬 조건을 적용하여 게시물 목록 조회
     *
     * N+1 방지를 위해 author, category, tags를 fetch join하며 커서 기반 페이지네이션으로 조회
     */
    override fun findPostsWithConditions(
        cursor: PostCursor?,
        size: Int,
        period: PostPeriod,
        sortType: PostSortType,
        authorId: UUID?,
        statuses: List<PostStatusEnum>?,
        categoryId: UUID?,
        visibleToUserId: UUID?,
        tagIds: List<UUID>?,
        viewerId: UUID?,
    ): List<PostWithSortValue> {
        return if (sortType == PostSortType.LATEST) {
            findLatestPostsWithConditions(
                cursor = cursor,
                size = size,
                period = period,
                authorId = authorId,
                statuses = statuses,
                categoryId = categoryId,
                visibleToUserId = visibleToUserId,
                tagIds = tagIds,
                viewerId = viewerId,
            )
        } else {
            findPostsWithStatsRanking(
                cursor = cursor,
                size = size,
                period = period,
                sortType = sortType,
                authorId = authorId,
                statuses = statuses,
                categoryId = categoryId,
                visibleToUserId = visibleToUserId,
                tagIds = tagIds,
                viewerId = viewerId,
            )
        }
    }

    private fun findLatestPostsWithConditions(
        cursor: PostCursor?,
        size: Int,
        period: PostPeriod,
        authorId: UUID?,
        statuses: List<PostStatusEnum>?,
        categoryId: UUID?,
        visibleToUserId: UUID?,
        tagIds: List<UUID>?,
        viewerId: UUID?,
    ): List<PostWithSortValue> {
        val cb = entityManager.criteriaBuilder
        val cq = cb.createQuery(Post::class.java)
        val root = cq.from(Post::class.java)
        cq.distinct(true)

        root.fetch<Post, User>(Post_.AUTHOR)
        root.fetch<Post, Category>(Post_.CATEGORY, JoinType.LEFT)
        root.fetch<Post, Tag>(Post_.TAGS, JoinType.LEFT)

        val predicates = mutableListOf<Predicate>()
        addBaseConditions(
            cb = cb,
            query = cq,
            root = root,
            authorId = authorId,
            statuses = statuses,
            categoryId = categoryId,
            visibleToUserId = visibleToUserId,
            tagIds = tagIds,
            viewerId = viewerId,
            predicates = predicates,
        )
        addLatestPeriodCondition(cb, root, period, predicates)
        cursor?.let { predicates.add(buildLatestCursorCondition(cb, root, it)) }

        if (predicates.isNotEmpty()) {
            cq.where(*predicates.toTypedArray())
        }
        cq.orderBy(
            cb.desc(root.get(EntityBase_.updatedAt)),
            cb.desc(root.get(EntityBase_.id)),
        )

        return entityManager.createQuery(cq)
            .setMaxResults(size)
            .resultList
            .distinctBy { it.id }
            .map { post -> PostWithSortValue(post = post, sortValue = post.updatedAt.toEpochMilli()) }
    }

    private fun findPostsWithStatsRanking(
        cursor: PostCursor?,
        size: Int,
        period: PostPeriod,
        sortType: PostSortType,
        authorId: UUID?,
        statuses: List<PostStatusEnum>?,
        categoryId: UUID?,
        visibleToUserId: UUID?,
        tagIds: List<UUID>?,
        viewerId: UUID?,
    ): List<PostWithSortValue> {
        val rankedPostIds =
            findRankedPostIdsWithStats(
                cursor = cursor,
                size = size,
                period = period,
                sortType = sortType,
                authorId = authorId,
                statuses = statuses,
                categoryId = categoryId,
                visibleToUserId = visibleToUserId,
                tagIds = tagIds,
                viewerId = viewerId,
            )

        return findPostsByRankedIdsInOrder(rankedPostIds)
    }

    private fun findRankedPostIdsWithStats(
        cursor: PostCursor?,
        size: Int,
        period: PostPeriod,
        sortType: PostSortType,
        authorId: UUID?,
        statuses: List<PostStatusEnum>?,
        categoryId: UUID?,
        visibleToUserId: UUID?,
        tagIds: List<UUID>?,
        viewerId: UUID?,
    ): List<RankedPostId> {
        val cb = entityManager.criteriaBuilder
        val cq = cb.createTupleQuery()
        val root = cq.from(Post::class.java)
        val statsRoot = cq.from(PostDailyStats::class.java)
        val postIdPath = root.get<UUID>(EntityBase_.id)
        val createdAtPath = root.get<Instant>(EntityBase_.createdAt)
        val sortExpression = dailyStatsSumExpression(cb, statsRoot, sortType)
        val predicates = mutableListOf<Predicate>()

        addBaseConditions(
            cb = cb,
            query = cq,
            root = root,
            authorId = authorId,
            statuses = statuses,
            categoryId = categoryId,
            visibleToUserId = visibleToUserId,
            tagIds = tagIds,
            viewerId = viewerId,
            predicates = predicates,
        )
        addStatsJoinCondition(cb, root, statsRoot, period, predicates)

        cq.multiselect(postIdPath, sortExpression)
        cq.where(*predicates.toTypedArray())
        cq.groupBy(postIdPath, createdAtPath)
        cursor?.let { cq.having(buildCountCursorCondition(cb, root, it, sortExpression)) }
        cq.orderBy(countSortOrders(cb, root, sortExpression))

        return entityManager.createQuery(cq)
            .setMaxResults(size)
            .resultList
            .map { tuple -> tuple.toRankedPostId() }
    }

    private fun Tuple.toRankedPostId(): RankedPostId =
        RankedPostId(
            postId = get(0) as UUID,
            sortValue = (get(1) as Number).toLong(),
        )

    private fun findPostsByRankedIdsInOrder(rankedPostIds: List<RankedPostId>): List<PostWithSortValue> {
        if (rankedPostIds.isEmpty()) {
            return emptyList()
        }

        val postIds = rankedPostIds.map { it.postId }
        val sortValueByPostId = rankedPostIds.associate { it.postId to it.sortValue }

        val cb = entityManager.criteriaBuilder
        val cq = cb.createQuery(Post::class.java)
        val root = cq.from(Post::class.java)
        cq.distinct(true)

        root.fetch<Post, User>(Post_.AUTHOR)
        root.fetch<Post, Category>(Post_.CATEGORY, JoinType.LEFT)
        root.fetch<Post, Tag>(Post_.TAGS, JoinType.LEFT)
        cq.where(root.get<UUID>(EntityBase_.id).`in`(postIds))

        val orderByPostId = postIds.withIndex().associate { (index, postId) -> postId to index }
        return entityManager.createQuery(cq)
            .resultList
            .distinctBy { it.id }
            .sortedBy { orderByPostId.getValue(it.id!!) }
            .map { post -> PostWithSortValue(post = post, sortValue = sortValueByPostId.getValue(post.id!!)) }
    }

    override fun findPostDetailByIdForViewer(
        postId: UUID,
        viewerId: UUID?,
    ): Post? {
        val cb = entityManager.criteriaBuilder
        val cq = cb.createQuery(Post::class.java)
        val root = cq.from(Post::class.java)
        cq.distinct(true)

        root.fetch<Post, User>(Post_.AUTHOR)
        root.fetch<Post, Tag>(Post_.TAGS, JoinType.LEFT)
        root.fetch<Post, Any>(Post_.CATEGORY, JoinType.LEFT)

        val predicates =
            mutableListOf<Predicate>(
                cb.equal(root.get(EntityBase_.id), postId),
            )

        addViewerBanCondition(cb, cq, root, viewerId, predicates)
        cq.where(*predicates.toTypedArray())

        return entityManager.createQuery(cq).resultList.firstOrNull()
    }

    private fun addBaseConditions(
        cb: CriteriaBuilder,
        query: CommonAbstractCriteria,
        root: Root<Post>,
        authorId: UUID?,
        statuses: List<PostStatusEnum>?,
        categoryId: UUID?,
        visibleToUserId: UUID?,
        tagIds: List<UUID>?,
        viewerId: UUID?,
        predicates: MutableList<Predicate>,
    ) {
        if (visibleToUserId != null) {
            val publishedPredicate = cb.equal(root.get(Post_.status), PostStatusEnum.PUBLISHED)
            val ownPrivatePostPredicate =
                cb.and(
                    cb.equal(root.get(Post_.author).get(EntityBase_.id), visibleToUserId),
                    cb.equal(root.get(Post_.status), PostStatusEnum.PRIVATE),
                )
            predicates.add(cb.or(publishedPredicate, ownPrivatePostPredicate))
        } else if (statuses != null) {
            predicates.add(root.get(Post_.status).`in`(statuses))
        } else {
            predicates.add(cb.equal(root.get(Post_.status), PostStatusEnum.PUBLISHED))
        }

        authorId?.let { predicates.add(cb.equal(root.get(Post_.author).get(EntityBase_.id), it)) }
        categoryId?.let { predicates.add(cb.equal(root.get(Post_.category).get(EntityBase_.id), it)) }
        tagIds?.let { addTagIdsCondition(cb, query, root, it, predicates) }
        addViewerBanCondition(cb, query, root, viewerId, predicates)
    }

    private fun addTagIdsCondition(
        cb: CriteriaBuilder,
        query: CommonAbstractCriteria,
        root: Root<Post>,
        tagIds: List<UUID>,
        predicates: MutableList<Predicate>,
    ) {
        val tagSubquery = query.subquery(Long::class.java)
        val tagPostRoot = tagSubquery.from(Post::class.java)
        val tagJoin = tagPostRoot.join<Post, Tag>(Post_.TAGS, JoinType.INNER)

        tagSubquery.select(cb.literal(1L))
        tagSubquery.where(
            cb.equal(tagPostRoot.get(EntityBase_.id), root.get(EntityBase_.id)),
            tagJoin.get<UUID>("id").`in`(tagIds),
        )
        predicates.add(cb.exists(tagSubquery))
    }

    private fun addLatestPeriodCondition(
        cb: CriteriaBuilder,
        root: Root<Post>,
        period: PostPeriod,
        predicates: MutableList<Predicate>,
    ) {
        period.days?.let { days ->
            val cutoffDate = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
            predicates.add(cb.greaterThanOrEqualTo(root.get(EntityBase_.createdAt), cutoffDate))
        }
    }

    private fun addStatsJoinCondition(
        cb: CriteriaBuilder,
        root: Root<Post>,
        statsRoot: Root<PostDailyStats>,
        period: PostPeriod,
        predicates: MutableList<Predicate>,
    ) {
        predicates.add(cb.equal(statsRoot.get(PostDailyStats_.post).get(EntityBase_.id), root.get(EntityBase_.id)))
        period.days?.let { days ->
            predicates.add(cb.greaterThanOrEqualTo(statsRoot.get(PostDailyStats_.statDate), statsCutoffDate(days)))
        }
    }

    private fun addViewerBanCondition(
        cb: CriteriaBuilder,
        query: CommonAbstractCriteria,
        root: Root<Post>,
        viewerId: UUID?,
        predicates: MutableList<Predicate>,
    ) {
        if (viewerId == null) {
            return
        }

        val banSubquery = query.subquery(Long::class.java)
        val banRoot = banSubquery.from(UserBan::class.java)

        banSubquery.select(cb.literal(1L))
        banSubquery.where(
            cb.equal(banRoot.get(UserBan_.user).get(EntityBase_.id), viewerId),
            cb.equal(
                banRoot.get(UserBan_.bannedUser).get(EntityBase_.id),
                root.get(Post_.author).get(EntityBase_.id),
            ),
        )

        predicates.add(cb.not(cb.exists(banSubquery)))
    }

    /**
     * 최신순 커서 조건 생성
     *
     * 최신순은 updatedAt 기준으로 정렬되며, 동일 시간대 게시물 구분을 위해 id를 보조 키로 사용합니다.
     * 조건: (updatedAt < cursor) OR (updatedAt = cursor AND id < cursorId)
     */
    private fun buildLatestCursorCondition(
        cb: CriteriaBuilder,
        root: Root<Post>,
        cursor: PostCursor,
    ): Predicate {
        val updatedAtLess = cb.lessThan(root.get(EntityBase_.updatedAt), cursor.createdAt)
        val updatedAtEqualAndIdLess =
            cb.and(
                cb.equal(root.get(EntityBase_.updatedAt), cursor.createdAt),
                cb.lessThan(root.get(EntityBase_.id), cursor.id),
            )
        return cb.or(updatedAtLess, updatedAtEqualAndIdLess)
    }

    /**
     * count 기반 정렬의 커서 조건 생성 (조회수, 추천수, 댓글수)
     *
     * count가 동일한 게시물 구분을 위해 createdAt, id를 보조 키로 사용
     * 조건: (count < cursor) OR (count = cursor AND createdAt < cursor) OR (count = cursor AND createdAt = cursor AND id < cursorId)
     */
    private fun buildCountCursorCondition(
        cb: CriteriaBuilder,
        root: Root<Post>,
        cursor: PostCursor,
        sortExpression: Expression<Long>,
    ): Predicate {
        val countLess = cb.lessThan(sortExpression, cursor.sortValue)
        val countEqualCreatedAtLess =
            cb.and(
                cb.equal(sortExpression, cursor.sortValue),
                cb.lessThan(root.get(EntityBase_.createdAt), cursor.createdAt),
            )
        val countEqualCreatedAtEqualIdLess =
            cb.and(
                cb.equal(sortExpression, cursor.sortValue),
                cb.equal(root.get(EntityBase_.createdAt), cursor.createdAt),
                cb.lessThan(root.get(EntityBase_.id), cursor.id),
            )
        return cb.or(countLess, countEqualCreatedAtLess, countEqualCreatedAtEqualIdLess)
    }

    private fun countSortOrders(
        cb: CriteriaBuilder,
        root: Root<Post>,
        sortExpression: Expression<Long>,
    ) = listOf(
        cb.desc(sortExpression),
        cb.desc(root.get(EntityBase_.createdAt)),
        cb.desc(root.get(EntityBase_.id)),
    )

    private fun dailyStatsSumExpression(
        cb: CriteriaBuilder,
        statsRoot: Root<PostDailyStats>,
        sortType: PostSortType,
    ): Expression<Long> = cb.coalesce(cb.sum(dailyStatsCountExpression(statsRoot, sortType)), 0L)

    private fun dailyStatsCountExpression(
        statsRoot: Root<PostDailyStats>,
        sortType: PostSortType,
    ): Path<Long> =
        when (sortType) {
            PostSortType.VIEW -> statsRoot.get(PostDailyStats_.viewCount)
            PostSortType.LIKE -> statsRoot.get(PostDailyStats_.likeCount)
            PostSortType.COMMENT -> statsRoot.get(PostDailyStats_.commentCount)
            else -> throw ApiException(PostStatus.INVALID_SORT_TYPE)
        }

    private fun statsCutoffDate(days: Int): LocalDate = LocalDate.now(ZoneOffset.UTC).minusDays(days.toLong())
}
