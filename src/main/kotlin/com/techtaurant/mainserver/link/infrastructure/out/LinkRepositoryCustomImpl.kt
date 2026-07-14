package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.jooq.tables.LinkDailyStats.Companion.LINK_DAILY_STATS
import com.techtaurant.mainserver.jooq.tables.LinkTags.Companion.LINK_TAGS
import com.techtaurant.mainserver.jooq.tables.Links.Companion.LINKS
import com.techtaurant.mainserver.jooq.tables.Tags.Companion.TAGS
import com.techtaurant.mainserver.jooq.tables.UserLinks.Companion.USER_LINKS
import com.techtaurant.mainserver.jooq.tables.records.LinksRecord
import com.techtaurant.mainserver.jooq.tables.records.TagsRecord
import com.techtaurant.mainserver.link.dto.LinkCursorV1
import com.techtaurant.mainserver.link.entity.Link
import com.techtaurant.mainserver.link.enums.LinkPeriod
import com.techtaurant.mainserver.link.enums.LinkSortType
import com.techtaurant.mainserver.post.entity.Tag
import jakarta.persistence.EntityManager
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Optional
import java.util.UUID

/** 공개 링크의 키셋 페이지를 jOOQ DSL로 조회한다. */
@Repository
class LinkRepositoryCustomImpl(
    private val dsl: DSLContext,
    private val entityManager: EntityManager,
) : LinkRepositoryCustom {
    override fun findById(id: UUID): Optional<Link> = Optional.ofNullable(findByIdWithTags(id))

    override fun existsById(id: UUID): Boolean = dsl.fetchExists(LINKS, LINKS.ID.eq(id))

    override fun findByUrl(url: String): Link? = fetchLinks(LINKS.URL.eq(url)).firstOrNull()

    override fun findByIdWithTags(linkId: UUID): Link? = fetchLinks(LINKS.ID.eq(linkId)).firstOrNull()

    override fun findAllWithTags(): List<Link> = fetchLinks(DSL.trueCondition())

    override fun findAllByConnectedUserIdWithTags(companyUserId: UUID): List<Link> =
        fetchLinks(
            DSL.exists(
                dsl.selectOne().from(USER_LINKS).where(USER_LINKS.LINK_ID.eq(LINKS.ID).and(USER_LINKS.USER_ID.eq(companyUserId))),
            ),
        )

    override fun findFirstPageIds(
        sourceCompanyUserId: UUID?,
        tag: String?,
        pageable: Pageable,
    ): List<UUID> = fetchLinkIds(baseCondition(sourceCompanyUserId, tag), pageable.pageSize)

    override fun findNextPageIds(
        sourceCompanyUserId: UUID?,
        tag: String?,
        cursorCreatedAt: Instant,
        cursorId: UUID,
        pageable: Pageable,
    ): List<UUID> =
        fetchLinkIds(
            baseCondition(sourceCompanyUserId, tag).and(
                LINKS.CREATED_AT_UTC.lt(cursorCreatedAt.atOffset(ZoneOffset.UTC))
                    .or(LINKS.CREATED_AT_UTC.eq(cursorCreatedAt.atOffset(ZoneOffset.UTC)).and(LINKS.ID.lt(cursorId))),
            ),
            pageable.pageSize,
        )

    override fun findAllByIdInWithTags(linkIds: List<UUID>): List<Link> =
        if (linkIds.isEmpty()) emptyList() else fetchLinks(LINKS.ID.`in`(linkIds))

    override fun findPublicLinkIds(
        cursor: LinkCursorV1?,
        limit: Int,
        sortType: LinkSortType,
        period: LinkPeriod,
        sourceCompanyUserId: UUID?,
        tag: String?,
    ): List<RankedLinkId> =
        run {
            // jOOQ는 Hibernate의 AUTO flush를 유발하지 않으므로 같은 트랜잭션의 쓰기를 먼저 반영한다.
            entityManager.flush()

            when (sortType) {
                LinkSortType.PUBLISHED -> findPublishedLinks(cursor, limit, period, sourceCompanyUserId, tag)
                LinkSortType.LIKE, LinkSortType.SAVE -> findStatRankedLinks(cursor, limit, sortType, period, sourceCompanyUserId, tag)
            }
        }

    private fun findPublishedLinks(
        cursor: LinkCursorV1?,
        limit: Int,
        period: LinkPeriod,
        sourceCompanyUserId: UUID?,
        tag: String?,
    ): List<RankedLinkId> {
        val conditions = mutableListOf(baseCondition(sourceCompanyUserId, tag))
        period.days?.let {
                days ->
            conditions += LINKS.CREATED_AT_UTC.ge(Instant.now().minus(days.toLong(), ChronoUnit.DAYS).atOffset(ZoneOffset.UTC))
        }
        cursor?.let { conditions += createdAtCursorCondition(it) }

        return dsl.select(LINKS.ID)
            .from(LINKS)
            .where(conditions)
            .orderBy(LINKS.CREATED_AT_UTC.desc(), LINKS.ID.desc())
            .limit(limit)
            .fetch { record -> RankedLinkId(linkId = requireNotNull(record[LINKS.ID]), sortValue = 0L) }
    }

    private fun findStatRankedLinks(
        cursor: LinkCursorV1?,
        limit: Int,
        sortType: LinkSortType,
        period: LinkPeriod,
        sourceCompanyUserId: UUID?,
        tag: String?,
    ): List<RankedLinkId> {
        val sortValue = dailyStatSum(sortType)
        val conditions = mutableListOf(baseCondition(sourceCompanyUserId, tag))
        period.days?.let { days -> conditions += LINK_DAILY_STATS.STAT_DATE.ge(statsCutoffDate(days)) }
        val cursorCondition = cursor?.let { statsCursorCondition(it, sortValue) }

        return dsl.select(LINKS.ID, sortValue)
            .from(LINKS)
            .join(LINK_DAILY_STATS).on(LINK_DAILY_STATS.LINK_ID.eq(LINKS.ID))
            .where(conditions)
            .groupBy(LINKS.ID, LINKS.CREATED_AT_UTC)
            .having(cursorCondition ?: DSL.trueCondition())
            .orderBy(sortValue.desc(), LINKS.CREATED_AT_UTC.desc(), LINKS.ID.desc())
            .limit(limit)
            .fetch { record ->
                RankedLinkId(
                    linkId = requireNotNull(record[LINKS.ID]),
                    sortValue = requireNotNull(record[sortValue]),
                )
            }
    }

    private fun baseCondition(
        sourceCompanyUserId: UUID?,
        tag: String?,
    ): Condition {
        val conditions = mutableListOf<Condition>()
        sourceCompanyUserId?.let { userId ->
            conditions +=
                DSL.exists(
                    dsl.selectOne()
                        .from(USER_LINKS)
                        .where(USER_LINKS.LINK_ID.eq(LINKS.ID).and(USER_LINKS.USER_ID.eq(userId))),
                )
        }
        tag?.let { tagName ->
            conditions +=
                DSL.exists(
                    dsl.selectOne()
                        .from(LINK_TAGS)
                        .join(TAGS).on(LINK_TAGS.TAG_ID.eq(TAGS.ID))
                        .where(LINK_TAGS.LINK_ID.eq(LINKS.ID).and(TAGS.NAME.eq(tagName))),
                )
        }

        return conditions.fold(DSL.trueCondition(), Condition::and)
    }

    private fun createdAtCursorCondition(cursor: LinkCursorV1): Condition {
        val cursorInstant = cursor.sortInstant.atOffset(ZoneOffset.UTC)
        return LINKS.CREATED_AT_UTC.lt(cursorInstant)
            .or(LINKS.CREATED_AT_UTC.eq(cursorInstant).and(LINKS.ID.lt(cursor.id)))
    }

    private fun statsCursorCondition(
        cursor: LinkCursorV1,
        sortValue: Field<Long>,
    ): Condition {
        val cursorInstant = cursor.sortInstant.atOffset(ZoneOffset.UTC)
        val sameSortValue = sortValue.eq(cursor.sortValue)

        return sortValue.lt(cursor.sortValue)
            .or(sameSortValue.and(LINKS.CREATED_AT_UTC.lt(cursorInstant)))
            .or(sameSortValue.and(LINKS.CREATED_AT_UTC.eq(cursorInstant)).and(LINKS.ID.lt(cursor.id)))
    }

    private fun dailyStatSum(sortType: LinkSortType): Field<Long> {
        val countField =
            when (sortType) {
                LinkSortType.LIKE -> LINK_DAILY_STATS.LIKE_COUNT
                LinkSortType.SAVE -> LINK_DAILY_STATS.SAVE_COUNT
                LinkSortType.PUBLISHED -> error("PUBLISHED 정렬에는 일별 집계가 없습니다")
            }

        return DSL.coalesce(DSL.sum(countField).cast(Long::class.java), 0L)
    }

    private fun statsCutoffDate(days: Int): LocalDate = LocalDate.now(ZoneOffset.UTC).minusDays(days.toLong())

    private fun fetchLinkIds(
        condition: Condition,
        limit: Int,
    ): List<UUID> =
        dsl.select(LINKS.ID)
            .from(LINKS)
            .where(condition)
            .orderBy(LINKS.CREATED_AT_UTC.desc(), LINKS.ID.desc())
            .limit(limit)
            .fetch(LINKS.ID)
            .filterNotNull()

    private fun fetchLinks(condition: Condition): List<Link> {
        if (entityManager.isJoinedToTransaction) {
            entityManager.flush()
        }
        val rows =
            dsl.select(LINKS.asterisk(), TAGS.asterisk())
                .from(LINKS)
                .leftJoin(LINK_TAGS).on(LINK_TAGS.LINK_ID.eq(LINKS.ID))
                .leftJoin(TAGS).on(LINK_TAGS.TAG_ID.eq(TAGS.ID))
                .where(condition)
                .fetch()

        return rows.groupBy { requireNotNull(it[LINKS.ID]) }.map { (_, linkRows) -> toLink(linkRows) }
    }

    private fun toLink(rows: List<org.jooq.Record>): Link {
        val linkRecord = rows.first().into(LINKS)
        val tags = rows.mapNotNull { row -> row.into(TAGS).takeIf { it.id != null }?.toTag() }.toMutableSet()

        return linkRecord.toLink(tags)
    }

    private fun LinksRecord.toLink(tags: MutableSet<Tag>): Link =
        Link(
            title = requireNotNull(title),
            url = requireNotNull(url),
            summary = requireNotNull(summary),
            tags = tags,
            viewCount = requireNotNull(viewCount),
            likeCount = requireNotNull(likeCount),
            createdAt = requireNotNull(createdAtUtc).toInstant(),
        ).apply {
            id = requireNotNull(this@toLink.id)
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }

    private fun TagsRecord.toTag(): Tag =
        Tag(requireNotNull(name)).apply {
            id = requireNotNull(this@toTag.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }
}
