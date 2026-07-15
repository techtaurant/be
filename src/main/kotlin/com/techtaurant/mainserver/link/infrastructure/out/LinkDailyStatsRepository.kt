package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkDailyStats
import org.springframework.data.repository.Repository
import java.time.LocalDate
import java.util.UUID

interface LinkDailyStatsRepository : Repository<LinkDailyStats, UUID>, LinkDailyStatsRepositoryCustom {
    override fun save(stats: LinkDailyStats): LinkDailyStats

    override fun saveAndFlush(stats: LinkDailyStats): LinkDailyStats

    override fun deleteAllInBatch()

    override fun findAll(): List<LinkDailyStats>

    override fun insertIfAbsent(
        id: UUID,
        linkId: UUID,
        statDate: LocalDate,
    ): Int

    override fun incrementViewCount(
        linkId: UUID,
        statDate: LocalDate,
    ): Int

    override fun incrementLikeCount(
        linkId: UUID,
        statDate: LocalDate,
    ): Int

    override fun decrementLikeCount(
        linkId: UUID,
        statDate: LocalDate,
    ): Int

    override fun incrementSaveCount(
        linkId: UUID,
        statDate: LocalDate,
    ): Int

    override fun decrementSaveCount(
        linkId: UUID,
        statDate: LocalDate,
    ): Int
}
