package com.techtaurant.mainserver.post.infrastructure.out

import com.github.f4b6a3.uuid.UuidCreator
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
) : PostRepository {
    companion object {
        private const val LIKE_ESCAPE_CHAR = '\\'
    }

    override fun save(post: Post): Post {
        val now = Instant.now()
        val postId = post.id ?: UuidCreator.getTimeOrderedEpoch().also { post.id = it }
        if (dsl.fetchExists(POSTS, POSTS.ID.eq(postId))) {
            // 조회수/좋아요/댓글수는 increment/decrement SQL이 원자적으로 소유하므로,
            // 조회 시점 값을 그대로 덮어써 동시 증감을 유실시키지 않도록 UPDATE 대상에서 제외한다.
            dsl.update(POSTS)
                .set(POSTS.TITLE, post.title)
                .set(POSTS.CONTENT, post.content)
                .set(POSTS.AUTHOR_ID, requireNotNull(post.author.id))
                .set(POSTS.CATEGORY_ID, post.category?.id)
                .set(POSTS.THUMBNAIL_IMAGE, post.thumbnailImage)
                .set(POSTS.STATUS, post.status.name)
                .set(POSTS.CREATED_AT_UTC, post.createdAt.atOffset(ZoneOffset.UTC))
                .set(POSTS.UPDATED_AT_UTC, now.atOffset(ZoneOffset.UTC))
                .where(POSTS.ID.eq(postId))
                .execute()
            syncPostTags(postId, post.tags.map { requireNotNull(it.id) }.toSet())
        } else {
            dsl.insertInto(POSTS)
                .set(POSTS.ID, postId)
                .set(POSTS.TITLE, post.title)
                .set(POSTS.CONTENT, post.content)
                .set(POSTS.AUTHOR_ID, requireNotNull(post.author.id))
                .set(POSTS.CATEGORY_ID, post.category?.id)
                .set(POSTS.VIEW_COUNT, post.viewCount)
                .set(POSTS.LIKE_COUNT, post.likeCount)
                .set(POSTS.COMMENT_COUNT, post.commentCount)
                .set(POSTS.THUMBNAIL_IMAGE, post.thumbnailImage)
                .set(POSTS.STATUS, post.status.name)
                .set(POSTS.CREATED_AT_UTC, post.createdAt.atOffset(ZoneOffset.UTC))
                .set(POSTS.UPDATED_AT_UTC, now.atOffset(ZoneOffset.UTC))
                .execute()
            insertPostTags(postId, post.tags.map { requireNotNull(it.id) })
        }
        post.updatedAt = now
        return post
    }

    /**
     * 저장하려는 태그 집합과 현재 post_tags 행을 비교해 실제 변경분만 반영한다.
     * 태그를 건드리지 않는 저장에서 전체 삭제/재삽입이 나가면 같은 게시물을 저장하는 트랜잭션끼리
     * post_tags에서 서로를 대기하게 되므로, 델타만 적용해 불필요한 쓰기와 잠금 경합을 없앤다.
     */
    private fun syncPostTags(
        postId: UUID,
        tagIds: Set<UUID>,
    ) {
        val currentTagIds =
            dsl.select(POST_TAGS.TAG_ID)
                .from(POST_TAGS)
                .where(POST_TAGS.POST_ID.eq(postId))
                .fetch(POST_TAGS.TAG_ID)
                .filterNotNull()
                .toSet()
        val removedTagIds = currentTagIds - tagIds
        val addedTagIds = tagIds - currentTagIds

        if (removedTagIds.isNotEmpty()) {
            dsl.deleteFrom(POST_TAGS).where(POST_TAGS.POST_ID.eq(postId).and(POST_TAGS.TAG_ID.`in`(removedTagIds))).execute()
        }
        insertPostTags(postId, addedTagIds)
    }

    private fun insertPostTags(
        postId: UUID,
        tagIds: Collection<UUID>,
    ) {
        tagIds.forEach { tagId ->
            dsl.insertInto(POST_TAGS)
                .set(POST_TAGS.POST_ID, postId)
                .set(POST_TAGS.TAG_ID, tagId)
                .execute()
        }
    }

    override fun saveAndFlush(post: Post): Post = save(post)

    override fun saveAll(posts: Iterable<Post>): List<Post> = posts.map(::save)

    override fun saveAllAndFlush(posts: Iterable<Post>): List<Post> = saveAll(posts)

    override fun delete(post: Post) {
        post.id?.let(::deleteById)
    }

    override fun deleteAll(posts: Iterable<Post>) {
        posts.mapNotNull(Post::id).forEach(::deleteById)
    }

    override fun deleteAll() {
        deleteAllInBatch()
    }

    override fun deleteAllInBatch() {
        dsl.deleteFrom(POSTS).execute()
    }

    override fun findAll(): List<Post> = fetchPosts(dsl.select(POSTS.ID).from(POSTS).fetch(POSTS.ID).filterNotNull())

    override fun getReferenceById(id: UUID): Post = findById(id).orElseThrow()

    override fun updateThumbnailImage(
        postId: UUID,
        thumbnailAttachmentId: UUID?,
    ) {
        dsl.update(POSTS)
            .set(POSTS.THUMBNAIL_IMAGE, thumbnailAttachmentId)
            .set(POSTS.UPDATED_AT_UTC, Instant.now().atOffset(ZoneOffset.UTC))
            .where(POSTS.ID.eq(postId))
            .execute()
    }

    override fun incrementViewCount(postId: UUID) {
        dsl.update(POSTS).set(POSTS.VIEW_COUNT, POSTS.VIEW_COUNT.plus(1)).where(POSTS.ID.eq(postId)).execute()
    }

    override fun incrementLikeCount(postId: UUID) {
        dsl.update(POSTS).set(POSTS.LIKE_COUNT, POSTS.LIKE_COUNT.plus(1)).where(POSTS.ID.eq(postId)).execute()
    }

    override fun decrementLikeCount(postId: UUID) {
        dsl.update(POSTS)
            .set(POSTS.LIKE_COUNT, POSTS.LIKE_COUNT.minus(1L))
            .where(POSTS.ID.eq(postId))
            .execute()
    }

    override fun incrementCommentCount(postId: UUID) {
        dsl.update(POSTS).set(POSTS.COMMENT_COUNT, POSTS.COMMENT_COUNT.plus(1)).where(POSTS.ID.eq(postId)).execute()
    }

    override fun decrementCommentCount(postId: UUID) {
        dsl.update(POSTS)
            .set(POSTS.COMMENT_COUNT, DSL.`when`(POSTS.COMMENT_COUNT.gt(0L), POSTS.COMMENT_COUNT.minus(1L)).otherwise(0L))
            .where(POSTS.ID.eq(postId))
            .execute()
    }

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
        keyword: String?,
    ): List<PostWithSortValue> {
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
                        keyword,
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
                        keyword,
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
        keyword: String?,
    ): List<RankedPostId> {
        val conditions = baseConditions(authorId, statuses, categoryId, visibleToUserId, tagIds, viewerId, keyword).toMutableList()
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
        keyword: String?,
    ): List<RankedPostId> {
        val sortValue = dailyStatSum(sortType)
        val conditions = baseConditions(authorId, statuses, categoryId, visibleToUserId, tagIds, viewerId, keyword).toMutableList()
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
        keyword: String?,
    ): List<Condition> {
        val conditions = mutableListOf<Condition>()
        conditions += visibilityCondition(statuses, visibleToUserId)
        authorId?.let { conditions += POSTS.AUTHOR_ID.eq(it) }
        categoryId?.let { conditions += POSTS.CATEGORY_ID.eq(it) }
        keyword?.let { conditions += keywordCondition(it) }
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

    /**
     * 제목 또는 본문에 검색어가 포함된 게시물을 찾는 조건을 만든다.
     *
     * 양쪽 컬럼 모두 `lower(컬럼) LIKE ?` 형태로 고정한다. 이 형태여야 pg_bigm 도입 시
     * `GIN (lower(컬럼) gin_bigm_ops)` 표현식 인덱스가 그대로 매칭된다.
     * gin_bigm_ops는 LIKE만 지원하므로 ILIKE를 렌더링하는 likeIgnoreCase를 쓰면 인덱스를 타지 못한다.
     */
    private fun keywordCondition(keyword: String): Condition {
        val pattern = "%${escapeLikeWildcards(keyword.lowercase())}%"

        return DSL.lower(POSTS.TITLE).like(pattern, LIKE_ESCAPE_CHAR)
            .or(DSL.lower(POSTS.CONTENT).like(pattern, LIKE_ESCAPE_CHAR))
    }

    /** 검색어에 포함된 LIKE 와일드카드를 리터럴로 취급하도록 이스케이프한다. 역슬래시를 먼저 처리해야 이중 이스케이프를 피한다. */
    private fun escapeLikeWildcards(keyword: String): String =
        keyword.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

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

    private fun <T> flushThen(query: () -> T): T = query()

    private fun fetchPostIds(condition: Condition): List<UUID> =
        dsl.select(POSTS.ID).from(POSTS).where(condition).fetch(POSTS.ID).filterNotNull()

    private fun fetchPostsInOrder(postIds: List<UUID?>): List<Post> {
        val normalizedIds = postIds.filterNotNull()
        val postsById = fetchPosts(normalizedIds).associateBy { it.id!! }
        return normalizedIds.mapNotNull(postsById::get)
    }

    private fun deleteById(postId: UUID) {
        dsl.deleteFrom(POST_TAGS).where(POST_TAGS.POST_ID.eq(postId)).execute()
        dsl.deleteFrom(POSTS).where(POSTS.ID.eq(postId)).execute()
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
