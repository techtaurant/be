package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.jooq.tables.Categories.Companion.CATEGORIES
import com.techtaurant.mainserver.jooq.tables.PostDailyStats.Companion.POST_DAILY_STATS
import com.techtaurant.mainserver.jooq.tables.PostTags.Companion.POST_TAGS
import com.techtaurant.mainserver.jooq.tables.Posts.Companion.POSTS
import com.techtaurant.mainserver.jooq.tables.Tags.Companion.TAGS
import com.techtaurant.mainserver.jooq.tables.UserBans.Companion.USER_BANS
import com.techtaurant.mainserver.jooq.tables.Users.Companion.USERS
import com.techtaurant.mainserver.jooq.tables.records.CategoriesRecord
import com.techtaurant.mainserver.jooq.tables.records.TagsRecord
import com.techtaurant.mainserver.jooq.tables.records.UsersRecord
import com.techtaurant.mainserver.post.application.PostWithSortValue
import com.techtaurant.mainserver.post.dto.PostCursor
import com.techtaurant.mainserver.post.entity.Category
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.entity.PostPeriod
import com.techtaurant.mainserver.post.entity.PostSortType
import com.techtaurant.mainserver.post.entity.Tag
import com.techtaurant.mainserver.post.enums.PostStatus
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import jakarta.persistence.EntityManager
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Optional
import java.util.UUID

/** 게시물 목록/상세 read model을 jOOQ로 조립한다. */
@Repository
class PostRepositoryCustomImpl(
    private val dsl: DSLContext,
    private val entityManager: EntityManager,
) : PostRepositoryCustom {
    override fun findById(id: UUID): Optional<Post> = flushThen { Optional.ofNullable(findPostDetailById(id)) }

    override fun findAllById(ids: Iterable<UUID>): List<Post> = flushThen { fetchPosts(ids.toList()) }

    override fun existsById(id: UUID): Boolean = flushThen { dsl.fetchExists(POSTS, POSTS.ID.eq(id)) }

    private data class RankedPostId(
        val postId: UUID,
        val sortValue: Long,
    )

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
        entityManager.flush()

        val rankedPostIds =
            when (sortType) {
                PostSortType.LATEST ->
                    findLatestPostIds(
                        cursor,
                        size,
                        period,
                        authorId,
                        statuses,
                        categoryId,
                        visibleToUserId,
                        tagIds,
                        viewerId,
                    )
                else ->
                    findStatRankedPostIds(
                        cursor,
                        size,
                        period,
                        sortType,
                        authorId,
                        statuses,
                        categoryId,
                        visibleToUserId,
                        tagIds,
                        viewerId,
                    )
            }

        val postsById = fetchPosts(rankedPostIds.map(RankedPostId::postId)).associateBy { it.id!! }
        return rankedPostIds.mapNotNull { ranked ->
            postsById[ranked.postId]?.let { post -> PostWithSortValue(post, ranked.sortValue) }
        }
    }

    override fun findPostDetailByIdForViewer(
        postId: UUID,
        viewerId: UUID?,
    ): Post? {
        entityManager.flush()
        return fetchPosts(listOf(postId), viewerId).firstOrNull()
    }

    override fun findAllByAuthorId(authorId: UUID): List<Post> = fetchPosts(fetchPostIds(POSTS.AUTHOR_ID.eq(authorId)))

    override fun findPostDetailById(postId: UUID): Post? = fetchPosts(listOf(postId)).firstOrNull()

    override fun findDraftsByAuthorWithCursor(
        authorId: UUID,
        cursorUpdatedAt: Instant,
        cursorId: UUID,
        limit: Int,
    ): List<Post> {
        val cursor = cursorUpdatedAt.atOffset(ZoneOffset.UTC)
        val afterCursor =
            POSTS.UPDATED_AT_UTC.lt(cursor)
                .or(POSTS.UPDATED_AT_UTC.eq(cursor).and(POSTS.ID.lt(cursorId)))

        return fetchPostsInOrder(
            dsl.select(POSTS.ID)
                .from(POSTS)
                .where(POSTS.AUTHOR_ID.eq(authorId).and(POSTS.STATUS.eq(PostStatusEnum.DRAFT.name)).and(afterCursor))
                .orderBy(POSTS.UPDATED_AT_UTC.desc(), POSTS.ID.desc())
                .limit(limit)
                .fetch(POSTS.ID),
        )
    }

    override fun findDraftsByAuthorFirstPage(
        authorId: UUID,
        limit: Int,
    ): List<Post> =
        fetchPostsInOrder(
            dsl.select(POSTS.ID)
                .from(POSTS)
                .where(POSTS.AUTHOR_ID.eq(authorId).and(POSTS.STATUS.eq(PostStatusEnum.DRAFT.name)))
                .orderBy(POSTS.UPDATED_AT_UTC.desc(), POSTS.ID.desc())
                .limit(limit)
                .fetch(POSTS.ID),
        )

    override fun findPostByIdWithAuthor(postId: UUID): Post? = fetchPosts(listOf(postId)).firstOrNull()

    override fun findPublishedPostsByIdIn(postIds: List<UUID>): List<Post> =
        fetchPosts(fetchPostIds(POSTS.ID.`in`(postIds).and(POSTS.STATUS.eq(PostStatusEnum.PUBLISHED.name))))

    override fun findStaleDraftsByAuthor(
        authorId: UUID,
        before: Instant,
    ): List<Post> =
        fetchPosts(
            fetchPostIds(
                POSTS.AUTHOR_ID.eq(authorId)
                    .and(POSTS.STATUS.eq(PostStatusEnum.DRAFT.name))
                    .and(POSTS.UPDATED_AT_UTC.lt(before.atOffset(ZoneOffset.UTC))),
            ),
        )

    private fun findLatestPostIds(
        cursor: PostCursor?,
        size: Int,
        period: PostPeriod,
        authorId: UUID?,
        statuses: List<PostStatusEnum>?,
        categoryId: UUID?,
        visibleToUserId: UUID?,
        tagIds: List<UUID>?,
        viewerId: UUID?,
    ): List<RankedPostId> {
        val conditions = baseConditions(authorId, statuses, categoryId, visibleToUserId, tagIds, viewerId).toMutableList()
        period.days?.let {
                days ->
            conditions += POSTS.CREATED_AT_UTC.ge(Instant.now().minus(days.toLong(), ChronoUnit.DAYS).atOffset(ZoneOffset.UTC))
        }
        cursor?.let { conditions += latestCursorCondition(it) }

        return dsl.select(POSTS.ID, POSTS.UPDATED_AT_UTC)
            .from(POSTS)
            .where(conditions)
            .orderBy(POSTS.UPDATED_AT_UTC.desc(), POSTS.ID.desc())
            .limit(size)
            .fetch { record ->
                RankedPostId(
                    postId = requireNotNull(record[POSTS.ID]),
                    sortValue = requireNotNull(record[POSTS.UPDATED_AT_UTC]).toInstant().toEpochMilli(),
                )
            }
    }

    private fun findStatRankedPostIds(
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
        val sortValue = dailyStatSum(sortType)
        val conditions = baseConditions(authorId, statuses, categoryId, visibleToUserId, tagIds, viewerId).toMutableList()
        period.days?.let { days -> conditions += POST_DAILY_STATS.STAT_DATE.ge(statsCutoffDate(days)) }
        val cursorCondition = cursor?.let { statsCursorCondition(it, sortValue) }

        return dsl.select(POSTS.ID, sortValue)
            .from(POSTS)
            .join(POST_DAILY_STATS).on(POST_DAILY_STATS.POST_ID.eq(POSTS.ID))
            .where(conditions)
            .groupBy(POSTS.ID, POSTS.CREATED_AT_UTC)
            .having(cursorCondition ?: DSL.trueCondition())
            .orderBy(sortValue.desc(), POSTS.CREATED_AT_UTC.desc(), POSTS.ID.desc())
            .limit(size)
            .fetch { record -> RankedPostId(requireNotNull(record[POSTS.ID]), requireNotNull(record[sortValue])) }
    }

    private fun baseConditions(
        authorId: UUID?,
        statuses: List<PostStatusEnum>?,
        categoryId: UUID?,
        visibleToUserId: UUID?,
        tagIds: List<UUID>?,
        viewerId: UUID?,
    ): List<Condition> {
        val conditions = mutableListOf<Condition>()
        conditions += visibilityCondition(statuses, visibleToUserId)
        authorId?.let { conditions += POSTS.AUTHOR_ID.eq(it) }
        categoryId?.let { conditions += POSTS.CATEGORY_ID.eq(it) }
        tagIds?.takeIf { it.isNotEmpty() }?.let { ids ->
            conditions += DSL.exists(dsl.selectOne().from(POST_TAGS).where(POST_TAGS.POST_ID.eq(POSTS.ID).and(POST_TAGS.TAG_ID.`in`(ids))))
        }
        viewerId?.let { userId ->
            conditions +=
                DSL.notExists(
                    dsl.selectOne().from(USER_BANS).where(USER_BANS.USER_ID.eq(userId).and(USER_BANS.BANNED_USER_ID.eq(POSTS.AUTHOR_ID))),
                )
        }
        return conditions
    }

    private fun visibilityCondition(
        statuses: List<PostStatusEnum>?,
        visibleToUserId: UUID?,
    ): Condition =
        if (visibleToUserId != null) {
            POSTS.STATUS.eq(PostStatusEnum.PUBLISHED.name)
                .or(POSTS.AUTHOR_ID.eq(visibleToUserId).and(POSTS.STATUS.eq(PostStatusEnum.PRIVATE.name)))
        } else if (statuses != null) {
            POSTS.STATUS.`in`(statuses.map(PostStatusEnum::name))
        } else {
            POSTS.STATUS.eq(PostStatusEnum.PUBLISHED.name)
        }

    private fun latestCursorCondition(cursor: PostCursor): Condition {
        val cursorInstant = cursor.createdAt.atOffset(ZoneOffset.UTC)
        return POSTS.UPDATED_AT_UTC.lt(cursorInstant)
            .or(POSTS.UPDATED_AT_UTC.eq(cursorInstant).and(POSTS.ID.lt(cursor.id)))
    }

    private fun statsCursorCondition(
        cursor: PostCursor,
        sortValue: Field<Long>,
    ): Condition {
        val cursorInstant = cursor.createdAt.atOffset(ZoneOffset.UTC)
        val sameSortValue = sortValue.eq(cursor.sortValue)
        return sortValue.lt(cursor.sortValue)
            .or(sameSortValue.and(POSTS.CREATED_AT_UTC.lt(cursorInstant)))
            .or(sameSortValue.and(POSTS.CREATED_AT_UTC.eq(cursorInstant)).and(POSTS.ID.lt(cursor.id)))
    }

    private fun dailyStatSum(sortType: PostSortType): Field<Long> {
        val countField =
            when (sortType) {
                PostSortType.VIEW -> POST_DAILY_STATS.VIEW_COUNT
                PostSortType.LIKE -> POST_DAILY_STATS.LIKE_COUNT
                PostSortType.COMMENT -> POST_DAILY_STATS.COMMENT_COUNT
                PostSortType.LATEST -> throw ApiException(PostStatus.INVALID_SORT_TYPE)
            }
        return DSL.coalesce(DSL.sum(countField).cast(Long::class.java), 0L)
    }

    private fun fetchPosts(
        postIds: List<UUID>,
        viewerId: UUID? = null,
    ): List<Post> {
        if (postIds.isEmpty()) return emptyList()

        val detailConditions = mutableListOf<Condition>(POSTS.ID.`in`(postIds))
        viewerId?.let { userId ->
            detailConditions +=
                DSL.notExists(
                    dsl.selectOne().from(USER_BANS).where(USER_BANS.USER_ID.eq(userId).and(USER_BANS.BANNED_USER_ID.eq(POSTS.AUTHOR_ID))),
                )
        }

        val rows =
            dsl.select(POSTS.asterisk(), USERS.asterisk(), CATEGORIES.asterisk(), TAGS.asterisk())
                .from(POSTS)
                .join(USERS).on(POSTS.AUTHOR_ID.eq(USERS.ID))
                .leftJoin(CATEGORIES).on(POSTS.CATEGORY_ID.eq(CATEGORIES.ID))
                .leftJoin(POST_TAGS).on(POST_TAGS.POST_ID.eq(POSTS.ID))
                .leftJoin(TAGS).on(POST_TAGS.TAG_ID.eq(TAGS.ID))
                .where(detailConditions)
                .fetch()

        return rows.groupBy { requireNotNull(it[POSTS.ID]) }.map { (_, postRows) -> toPost(postRows) }
    }

    private fun <T> flushThen(query: () -> T): T {
        if (entityManager.isJoinedToTransaction) {
            entityManager.flush()
        }
        return query()
    }

    private fun fetchPostIds(condition: Condition): List<UUID> =
        dsl.select(POSTS.ID).from(POSTS).where(condition).fetch(POSTS.ID).filterNotNull()

    private fun fetchPostsInOrder(postIds: List<UUID?>): List<Post> {
        val normalizedIds = postIds.filterNotNull()
        val postsById = fetchPosts(normalizedIds).associateBy { it.id!! }
        return normalizedIds.mapNotNull(postsById::get)
    }

    private fun toPost(rows: List<Record>): Post {
        val firstRow = rows.first()
        val postRecord = firstRow.into(POSTS)
        val author = firstRow.into(USERS).toUser()
        val category = firstRow.into(CATEGORIES).takeIf { it.id != null }?.toCategory(author)
        val tags = rows.mapNotNull { row -> row.into(TAGS).takeIf { it.id != null }?.toTag() }.toMutableSet()

        return Post(
            title = requireNotNull(postRecord.title),
            content = requireNotNull(postRecord.content),
            author = author,
            category = category,
            tags = tags,
            viewCount = requireNotNull(postRecord.viewCount),
            likeCount = requireNotNull(postRecord.likeCount),
            commentCount = requireNotNull(postRecord.commentCount),
            thumbnailImage = postRecord.thumbnailImage,
            status = PostStatusEnum.valueOf(requireNotNull(postRecord.status)),
        ).apply {
            id = requireNotNull(postRecord.id)
            createdAt = requireNotNull(postRecord.createdAtUtc).toInstant()
            updatedAt = requireNotNull(postRecord.updatedAtUtc).toInstant()
        }
    }

    private fun UsersRecord.toUser(): User =
        User(
            name = requireNotNull(name),
            email = requireNotNull(email),
            provider = OAuthProvider.valueOf(requireNotNull(provider)),
            identifier = requireNotNull(identifier),
            role = UserRole.valueOf(requireNotNull(role)),
            profileImageUrl = profileImageUrl.orEmpty(),
            serviceProfileImageAttachmentId = serviceProfileImageAttachmentId,
        ).apply {
            id = requireNotNull(this@toUser.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }

    private fun CategoriesRecord.toCategory(owner: User): Category =
        Category(user = owner, name = requireNotNull(name), path = requireNotNull(path), depth = requireNotNull(depth)).apply {
            id = requireNotNull(this@toCategory.id)
            parent = parentId?.let { parentId -> Category(owner, "", "", 1).apply { id = parentId } }
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }

    private fun TagsRecord.toTag(): Tag =
        Tag(requireNotNull(name)).apply {
            id = requireNotNull(this@toTag.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }

    private fun statsCutoffDate(days: Int): LocalDate = LocalDate.now(ZoneOffset.UTC).minusDays(days.toLong())
}
