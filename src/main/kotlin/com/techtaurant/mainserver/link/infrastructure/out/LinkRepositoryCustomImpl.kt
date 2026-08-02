package com.techtaurant.mainserver.link.infrastructure.out

import com.github.f4b6a3.uuid.UuidCreator
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
) : LinkRepository {
    override fun save(link: Link): Link {
        val id = link.id ?: UuidCreator.getTimeOrderedEpoch().also { link.id = it }
        val now = Instant.now()
        if (dsl.fetchExists(LINKS, LINKS.ID.eq(id))) {
            // 조회수/좋아요는 increment/decrement SQL이 원자적으로 소유하므로,
            // 크롤 갱신이 조회 시점 값을 그대로 덮어써 동시 증감을 유실시키지 않도록 UPDATE 대상에서 제외한다.
            dsl.update(LINKS).set(LINKS.TITLE, link.title).set(LINKS.URL, link.url).set(LINKS.SUMMARY, link.summary)
                .set(LINKS.CREATED_AT_UTC, link.createdAt.atOffset(ZoneOffset.UTC))
                .set(LINKS.UPDATED_AT_UTC, now.atOffset(ZoneOffset.UTC)).where(LINKS.ID.eq(id)).execute()
            syncLinkTags(id, link.tags.map { requireNotNull(it.id) }.toSet())
        } else {
            dsl.insertInto(LINKS).set(LINKS.ID, id).set(LINKS.TITLE, link.title).set(LINKS.URL, link.url).set(LINKS.SUMMARY, link.summary)
                .set(
                    LINKS.VIEW_COUNT,
                    link.viewCount,
                ).set(
                    LINKS.LIKE_COUNT,
                    link.likeCount,
                ).set(
                    LINKS.CREATED_AT_UTC,
                    link.createdAt.atOffset(ZoneOffset.UTC),
                ).set(LINKS.UPDATED_AT_UTC, now.atOffset(ZoneOffset.UTC)).execute()
            insertLinkTags(id, link.tags.map { requireNotNull(it.id) })
        }
        link.updatedAt = now
        return link
    }

    /**
     * 저장하려는 태그 집합과 현재 link_tags 행을 비교해 실제 변경분만 반영한다.
     * 크롤 갱신은 태그를 바꾸지 않으므로, 전체 삭제/재삽입을 두면 실행마다 기존 링크의 태그 행이
     * 그대로 재작성되고 같은 링크를 동시에 갱신하는 배치끼리 잠금 경합이 생긴다.
     */
    private fun syncLinkTags(
        linkId: UUID,
        tagIds: Set<UUID>,
    ) {
        val currentTagIds =
            dsl.select(LINK_TAGS.TAG_ID)
                .from(LINK_TAGS)
                .where(LINK_TAGS.LINK_ID.eq(linkId))
                .fetch(LINK_TAGS.TAG_ID)
                .filterNotNull()
                .toSet()
        val removedTagIds = currentTagIds - tagIds
        val addedTagIds = tagIds - currentTagIds

        if (removedTagIds.isNotEmpty()) {
            dsl.deleteFrom(LINK_TAGS).where(LINK_TAGS.LINK_ID.eq(linkId).and(LINK_TAGS.TAG_ID.`in`(removedTagIds))).execute()
        }
        insertLinkTags(linkId, addedTagIds)
    }

    private fun insertLinkTags(
        linkId: UUID,
        tagIds: Collection<UUID>,
    ) {
        tagIds.forEach { tagId ->
            dsl.insertInto(LINK_TAGS).set(LINK_TAGS.LINK_ID, linkId).set(LINK_TAGS.TAG_ID, tagId).execute()
        }
    }

    override fun saveAndFlush(link: Link): Link = save(link)

    override fun delete(link: Link) {
        link.id?.let { id ->
            dsl.deleteFrom(LINK_TAGS).where(LINK_TAGS.LINK_ID.eq(id)).execute()
            dsl.deleteFrom(LINKS).where(LINKS.ID.eq(id)).execute()
        }
    }

    override fun deleteAll() {
        dsl.deleteFrom(LINKS).execute()
    }

    override fun deleteAllInBatch() {
        dsl.deleteFrom(LINKS).execute()
    }

    override fun findAll(): List<Link> = fetchLinks(DSL.trueCondition())

    override fun incrementViewCount(linkId: UUID) {
        dsl.update(LINKS).set(LINKS.VIEW_COUNT, LINKS.VIEW_COUNT.plus(1L)).where(LINKS.ID.eq(linkId)).execute()
    }

    override fun incrementLikeCount(linkId: UUID) {
        dsl.update(LINKS).set(LINKS.LIKE_COUNT, LINKS.LIKE_COUNT.plus(1L)).where(LINKS.ID.eq(linkId)).execute()
    }

    override fun decrementLikeCount(linkId: UUID) {
        dsl.update(LINKS).set(LINKS.LIKE_COUNT, LINKS.LIKE_COUNT.minus(1L)).where(LINKS.ID.eq(linkId)).execute()
    }

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
