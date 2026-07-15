package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkDailyStats
import java.time.LocalDate
import java.util.UUID

interface LinkDailyStatsRepositoryCustom {
    fun save(stats: LinkDailyStats): LinkDailyStats

    fun saveAndFlush(stats: LinkDailyStats): LinkDailyStats

    fun deleteAllInBatch()

    fun findAll(): List<LinkDailyStats>

    fun insertIfAbsent(
        id: UUID,
        linkId: UUID,
        statDate: LocalDate,
    ): Int

    fun incrementViewCount(
        linkId: UUID,
        statDate: LocalDate,
    ): Int

    fun incrementLikeCount(
        linkId: UUID,
        statDate: LocalDate,
    ): Int

    fun decrementLikeCount(
        linkId: UUID,
        statDate: LocalDate,
    ): Int

    fun incrementSaveCount(
        linkId: UUID,
        statDate: LocalDate,
    ): Int

    fun decrementSaveCount(
        linkId: UUID,
        statDate: LocalDate,
    ): Int

    fun aggregateStatsByLinkIds(linkIds: List<UUID>): List<LinkStatsAggregateProjection>
}
