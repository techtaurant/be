package com.techtaurant.mainserver.link.infrastructure.out

import com.github.f4b6a3.uuid.UuidCreator
import com.techtaurant.mainserver.jooq.tables.LinkDailyStats.Companion.LINK_DAILY_STATS
import com.techtaurant.mainserver.jooq.tables.records.LinkDailyStatsRecord
import com.techtaurant.mainserver.link.entity.Link
import com.techtaurant.mainserver.link.entity.LinkDailyStats
import org.jooq.DSLContext
import org.jooq.impl.DSL.coalesce
import org.jooq.impl.DSL.sum
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@Repository
class LinkDailyStatsRepositoryCustomImpl(
    private val dsl: DSLContext,
) : LinkDailyStatsRepository {
    override fun save(stats: LinkDailyStats): LinkDailyStats {
        val id = stats.id ?: UuidCreator.getTimeOrderedEpoch().also { stats.id = it }
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        dsl.insertInto(LINK_DAILY_STATS)
            .set(LINK_DAILY_STATS.ID, id)
            .set(LINK_DAILY_STATS.LINK_ID, requireNotNull(stats.link.id))
            .set(LINK_DAILY_STATS.STAT_DATE, stats.statDate)
            .set(LINK_DAILY_STATS.VIEW_COUNT, stats.viewCount)
            .set(LINK_DAILY_STATS.LIKE_COUNT, stats.likeCount)
            .set(LINK_DAILY_STATS.SAVE_COUNT, stats.saveCount)
            .set(LINK_DAILY_STATS.CREATED_AT_UTC, stats.createdAt.atOffset(ZoneOffset.UTC))
            .set(LINK_DAILY_STATS.UPDATED_AT_UTC, now)
            .onConflict(LINK_DAILY_STATS.ID)
            .doUpdate()
            .set(LINK_DAILY_STATS.VIEW_COUNT, stats.viewCount)
            .set(LINK_DAILY_STATS.LIKE_COUNT, stats.likeCount)
            .set(LINK_DAILY_STATS.SAVE_COUNT, stats.saveCount)
            .set(LINK_DAILY_STATS.UPDATED_AT_UTC, now)
            .execute()
        stats.updatedAt = now.toInstant()
        return stats
    }

    override fun saveAndFlush(stats: LinkDailyStats): LinkDailyStats = save(stats)

    override fun deleteAllInBatch() {
        dsl.deleteFrom(LINK_DAILY_STATS).execute()
    }

    override fun findAll(): List<LinkDailyStats> = dsl.selectFrom(LINK_DAILY_STATS).fetch().map { it.toLinkDailyStats() }

    override fun insertIfAbsent(
        id: UUID,
        linkId: UUID,
        statDate: LocalDate,
    ): Int =
        dsl.insertInto(LINK_DAILY_STATS)
            .set(LINK_DAILY_STATS.ID, id)
            .set(LINK_DAILY_STATS.LINK_ID, linkId)
            .set(LINK_DAILY_STATS.STAT_DATE, statDate)
            .set(LINK_DAILY_STATS.VIEW_COUNT, 0L)
            .set(LINK_DAILY_STATS.LIKE_COUNT, 0L)
            .set(LINK_DAILY_STATS.SAVE_COUNT, 0L)
            .set(LINK_DAILY_STATS.CREATED_AT_UTC, Instant.now().atOffset(ZoneOffset.UTC))
            .set(LINK_DAILY_STATS.UPDATED_AT_UTC, Instant.now().atOffset(ZoneOffset.UTC))
            .onConflict(LINK_DAILY_STATS.LINK_ID, LINK_DAILY_STATS.STAT_DATE)
            .doNothing()
            .execute()

    override fun incrementViewCount(
        linkId: UUID,
        statDate: LocalDate,
    ): Int = increment(linkId, statDate, LINK_DAILY_STATS.VIEW_COUNT, 1L)

    override fun incrementLikeCount(
        linkId: UUID,
        statDate: LocalDate,
    ): Int = increment(linkId, statDate, LINK_DAILY_STATS.LIKE_COUNT, 1L)

    override fun decrementLikeCount(
        linkId: UUID,
        statDate: LocalDate,
    ): Int = increment(linkId, statDate, LINK_DAILY_STATS.LIKE_COUNT, -1L)

    override fun incrementSaveCount(
        linkId: UUID,
        statDate: LocalDate,
    ): Int = increment(linkId, statDate, LINK_DAILY_STATS.SAVE_COUNT, 1L)

    override fun decrementSaveCount(
        linkId: UUID,
        statDate: LocalDate,
    ): Int = increment(linkId, statDate, LINK_DAILY_STATS.SAVE_COUNT, -1L)

    private fun increment(
        linkId: UUID,
        statDate: LocalDate,
        field: org.jooq.TableField<*, Long?>,
        amount: Long,
    ): Int =
        dsl.update(LINK_DAILY_STATS)
            .set(field, field.plus(amount))
            .set(LINK_DAILY_STATS.UPDATED_AT_UTC, Instant.now().atOffset(ZoneOffset.UTC))
            .where(LINK_DAILY_STATS.LINK_ID.eq(linkId).and(LINK_DAILY_STATS.STAT_DATE.eq(statDate)))
            .execute()

    override fun aggregateStatsByLinkIds(linkIds: List<UUID>): List<LinkStatsAggregateProjection> {
        if (linkIds.isEmpty()) {
            return emptyList()
        }

        val viewCount = coalesce(sum(LINK_DAILY_STATS.VIEW_COUNT), 0L).cast(Long::class.java)
        val likeCount = coalesce(sum(LINK_DAILY_STATS.LIKE_COUNT), 0L).cast(Long::class.java)
        val saveCount = coalesce(sum(LINK_DAILY_STATS.SAVE_COUNT), 0L).cast(Long::class.java)

        return dsl.select(LINK_DAILY_STATS.LINK_ID, viewCount, likeCount, saveCount)
            .from(LINK_DAILY_STATS)
            .where(LINK_DAILY_STATS.LINK_ID.`in`(linkIds))
            .groupBy(LINK_DAILY_STATS.LINK_ID)
            .fetch { record ->
                LinkStatsAggregate(
                    requireNotNull(record[LINK_DAILY_STATS.LINK_ID]),
                    requireNotNull(record[viewCount]),
                    requireNotNull(record[likeCount]),
                    requireNotNull(record[saveCount]),
                )
            }
    }

    private data class LinkStatsAggregate(
        private val linkId: UUID,
        private val viewCount: Long,
        private val likeCount: Long,
        private val saveCount: Long,
    ) : LinkStatsAggregateProjection {
        override fun getLinkId(): UUID = linkId

        override fun getViewCount(): Long = viewCount

        override fun getLikeCount(): Long = likeCount

        override fun getSaveCount(): Long = saveCount
    }

    private fun LinkDailyStatsRecord.toLinkDailyStats(): LinkDailyStats =
        LinkDailyStats(
            link = Link("", "", "").apply { id = requireNotNull(linkId) },
            statDate = requireNotNull(statDate),
            viewCount = requireNotNull(viewCount),
            likeCount = requireNotNull(likeCount),
            saveCount = requireNotNull(saveCount),
        ).apply {
            id = requireNotNull(this@toLinkDailyStats.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }
}
