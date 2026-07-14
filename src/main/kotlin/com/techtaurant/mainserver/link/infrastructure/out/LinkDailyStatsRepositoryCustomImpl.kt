package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.jooq.tables.LinkDailyStats.Companion.LINK_DAILY_STATS
import org.jooq.DSLContext
import org.jooq.impl.DSL.coalesce
import org.jooq.impl.DSL.sum
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class LinkDailyStatsRepositoryCustomImpl(
    private val dsl: DSLContext,
) : LinkDailyStatsRepositoryCustom {
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
}
