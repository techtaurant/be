package com.techtaurant.mainserver.post.infrastructure.out

import com.github.f4b6a3.uuid.UuidCreator
import com.techtaurant.mainserver.jooq.tables.Categories.Companion.CATEGORIES
import com.techtaurant.mainserver.jooq.tables.Posts.Companion.POSTS
import com.techtaurant.mainserver.jooq.tables.records.CategoriesRecord
import com.techtaurant.mainserver.post.entity.Category
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Repository
class CategoryRepositoryCustomImpl(
    private val dsl: DSLContext,
) : CategoryRepository {
    private val descendant = CATEGORIES.`as`("descendant")

    override fun save(category: Category): Category {
        val id = category.id ?: UuidCreator.getTimeOrderedEpoch().also { category.id = it }
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        dsl.insertInto(CATEGORIES)
            .set(CATEGORIES.ID, id)
            .set(CATEGORIES.USER_ID, requireNotNull(category.user.id))
            .set(CATEGORIES.NAME, category.name)
            .set(CATEGORIES.PATH, category.path)
            .set(CATEGORIES.DEPTH, category.depth)
            .set(CATEGORIES.PARENT_ID, category.parent?.id)
            .set(CATEGORIES.CREATED_AT_UTC, category.createdAt.atOffset(ZoneOffset.UTC))
            .set(CATEGORIES.UPDATED_AT_UTC, now)
            .onConflict(CATEGORIES.ID)
            .doUpdate()
            .set(CATEGORIES.NAME, category.name)
            .set(CATEGORIES.PATH, category.path)
            .set(CATEGORIES.DEPTH, category.depth)
            .set(CATEGORIES.PARENT_ID, category.parent?.id)
            .set(CATEGORIES.UPDATED_AT_UTC, now)
            .execute()
        category.updatedAt = now.toInstant()
        return category
    }

    override fun deleteAllInBatch() {
        dsl.deleteFrom(CATEGORIES).execute()
    }

    override fun findByUserAndPath(
        user: User,
        path: String,
    ): Category? = fetchCategories(CATEGORIES.USER_ID.eq(user.id).and(CATEGORIES.PATH.eq(path)), user).firstOrNull()

    override fun findByUserId(userId: UUID): List<Category> =
        fetchCategories(CATEGORIES.USER_ID.eq(userId), userReference(userId), CATEGORIES.DEPTH.asc(), CATEGORIES.NAME.asc())

    override fun findByUserIdAndPathPrefix(
        userId: UUID,
        pathPrefix: String,
    ): List<Category> =
        fetchCategories(
            CATEGORIES.USER_ID.eq(userId).and(CATEGORIES.PATH.like("$pathPrefix%")),
            userReference(userId),
            CATEGORIES.DEPTH.asc(),
            CATEGORIES.NAME.asc(),
        )

    override fun findByUserIdWithPostCount(userId: UUID): List<CategoryWithPostCountProjection> =
        fetchCategoryCounts(CATEGORIES.USER_ID.eq(userId))

    override fun findByUserIdAndPathPrefixWithPostCount(
        userId: UUID,
        pathPrefix: String,
    ): List<CategoryWithPostCountProjection> = fetchCategoryCounts(CATEGORIES.USER_ID.eq(userId).and(CATEGORIES.PATH.like("$pathPrefix%")))

    private fun fetchCategories(
        condition: Condition,
        owner: User,
        vararg orderBy: org.jooq.SortField<*>,
    ): List<Category> =
        dsl.selectFrom(CATEGORIES).where(condition).orderBy(orderBy.asList()).fetch().map { record -> record.toCategory(owner) }

    private fun fetchCategoryCounts(condition: Condition): List<CategoryWithPostCountProjection> {
        val postCount = DSL.coalesce(DSL.countDistinct(POSTS.ID).cast(Long::class.java), 0L)
        val descendantPath = descendant.PATH.eq(CATEGORIES.PATH).or(descendant.PATH.like(CATEGORIES.PATH.concat("/%")))

        return dsl.select(CATEGORIES.ID, CATEGORIES.NAME, CATEGORIES.PATH, CATEGORIES.DEPTH, CATEGORIES.PARENT_ID, postCount)
            .from(CATEGORIES)
            .leftJoin(descendant)
            .on(descendant.USER_ID.eq(CATEGORIES.USER_ID).and(descendantPath))
            .leftJoin(POSTS)
            .on(POSTS.CATEGORY_ID.eq(descendant.ID))
            .where(condition)
            .groupBy(CATEGORIES.ID, CATEGORIES.NAME, CATEGORIES.PATH, CATEGORIES.DEPTH, CATEGORIES.PARENT_ID)
            .orderBy(CATEGORIES.DEPTH.asc(), CATEGORIES.NAME.asc())
            .fetch { record ->
                CategoryPostCount(
                    id = requireNotNull(record[CATEGORIES.ID]),
                    name = requireNotNull(record[CATEGORIES.NAME]),
                    path = requireNotNull(record[CATEGORIES.PATH]),
                    depth = requireNotNull(record[CATEGORIES.DEPTH]),
                    parentId = record[CATEGORIES.PARENT_ID],
                    postCount = requireNotNull(record[postCount]),
                )
            }
    }

    private fun CategoriesRecord.toCategory(owner: User): Category =
        Category(owner, requireNotNull(name), requireNotNull(path), requireNotNull(depth)).apply {
            id = requireNotNull(this@toCategory.id)
            parent = parentId?.let { parentId -> Category(owner, "", "", 1).apply { id = parentId } }
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }

    private fun userReference(userId: UUID): User = User("", "", OAuthProvider.GOOGLE, "", UserRole.USER, "").apply { id = userId }

    private data class CategoryPostCount(
        private val id: UUID,
        private val name: String,
        private val path: String,
        private val depth: Int,
        private val parentId: UUID?,
        private val postCount: Long,
    ) : CategoryWithPostCountProjection {
        override fun getId(): UUID = id

        override fun getName(): String = name

        override fun getPath(): String = path

        override fun getDepth(): Int = depth

        override fun getParentId(): UUID? = parentId

        override fun getPostCount(): Long = postCount
    }
}
