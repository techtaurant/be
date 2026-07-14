package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.jooq.tables.PostTags.Companion.POST_TAGS
import com.techtaurant.mainserver.jooq.tables.Tags.Companion.TAGS
import com.techtaurant.mainserver.jooq.tables.records.TagsRecord
import com.techtaurant.mainserver.post.entity.Tag
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class TagRepositoryCustomImpl(
    private val dsl: DSLContext,
) : TagRepositoryCustom {
    override fun findByName(name: String): Tag? = dsl.selectFrom(TAGS).where(TAGS.NAME.eq(name)).fetchOne()?.toTag()

    override fun findByNameIn(names: Collection<String>): List<Tag> =
        if (names.isEmpty()) emptyList() else dsl.selectFrom(TAGS).where(TAGS.NAME.`in`(names)).fetch().map { record -> record.toTag() }

    override fun findAllWithPostCount(
        name: String?,
        limit: Int,
    ): List<TagWithPostCountProjection> = fetchTagCounts(nameCondition(name), null, limit)

    override fun findAllWithPostCountAfterCursor(
        name: String?,
        lastPostCount: Long,
        lastTagId: UUID,
        limit: Int,
    ): List<TagWithPostCountProjection> {
        val postCount = postCountField()
        val cursorCondition = postCount.lt(lastPostCount).or(postCount.eq(lastPostCount).and(TAGS.ID.gt(lastTagId)))
        return fetchTagCounts(nameCondition(name), cursorCondition, limit)
    }

    private fun fetchTagCounts(
        condition: Condition,
        cursorCondition: Condition?,
        limit: Int,
    ): List<TagWithPostCountProjection> {
        val postCount = postCountField()
        return dsl.select(TAGS.ID, TAGS.NAME, TAGS.CREATED_AT_UTC, TAGS.UPDATED_AT_UTC, postCount)
            .from(TAGS)
            .join(POST_TAGS)
            .on(TAGS.ID.eq(POST_TAGS.TAG_ID))
            .where(condition)
            .groupBy(TAGS.ID, TAGS.NAME, TAGS.CREATED_AT_UTC, TAGS.UPDATED_AT_UTC)
            .having(cursorCondition ?: DSL.trueCondition())
            .orderBy(postCount.desc(), TAGS.ID.asc())
            .limit(limit)
            .fetch { record ->
                TagPostCount(
                    id = requireNotNull(record[TAGS.ID]),
                    name = requireNotNull(record[TAGS.NAME]),
                    createdAt = requireNotNull(record[TAGS.CREATED_AT_UTC]).toInstant(),
                    updatedAt = requireNotNull(record[TAGS.UPDATED_AT_UTC]).toInstant(),
                    postCount = requireNotNull(record[postCount]),
                )
            }
    }

    private fun nameCondition(name: String?): Condition = name?.let { TAGS.NAME.likeIgnoreCase("%$it%") } ?: DSL.trueCondition()

    private fun postCountField(): Field<Long> = DSL.coalesce(DSL.count(POST_TAGS.POST_ID).cast(Long::class.java), 0L)

    private fun TagsRecord.toTag(): Tag =
        Tag(requireNotNull(name)).apply {
            id = requireNotNull(this@toTag.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }

    private data class TagPostCount(
        private val id: UUID,
        private val name: String,
        private val createdAt: Instant,
        private val updatedAt: Instant,
        private val postCount: Long,
    ) : TagWithPostCountProjection {
        override fun getId(): UUID = id

        override fun getName(): String = name

        override fun getCreatedAt(): Instant = createdAt

        override fun getUpdatedAt(): Instant = updatedAt

        override fun getPostCount(): Long = postCount
    }
}
