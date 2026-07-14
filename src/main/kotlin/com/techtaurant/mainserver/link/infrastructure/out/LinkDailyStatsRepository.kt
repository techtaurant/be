package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkDailyStats
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface LinkDailyStatsRepository : JpaRepository<LinkDailyStats, UUID>, LinkDailyStatsRepositoryCustom {
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(
        """
        INSERT INTO link_daily_stats (id, link_id, stat_date, view_count, like_count, save_count, created_at_utc, updated_at_utc)
        VALUES (:id, :linkId, :statDate, 0, 0, 0, NOW(), NOW())
        ON CONFLICT (link_id, stat_date) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("id") id: UUID,
        @Param("linkId") linkId: UUID,
        @Param("statDate") statDate: LocalDate,
    ): Int

    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(
        """
        UPDATE link_daily_stats
        SET view_count = view_count + 1, updated_at_utc = NOW()
        WHERE link_id = :linkId AND stat_date = :statDate
        """,
        nativeQuery = true,
    )
    fun incrementViewCount(
        @Param("linkId") linkId: UUID,
        @Param("statDate") statDate: LocalDate,
    ): Int

    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(
        """
        UPDATE link_daily_stats
        SET like_count = like_count + 1, updated_at_utc = NOW()
        WHERE link_id = :linkId AND stat_date = :statDate
        """,
        nativeQuery = true,
    )
    fun incrementLikeCount(
        @Param("linkId") linkId: UUID,
        @Param("statDate") statDate: LocalDate,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE link_daily_stats
        SET like_count = like_count - 1, updated_at_utc = NOW()
        WHERE link_id = :linkId AND stat_date = :statDate
        """,
        nativeQuery = true,
    )
    fun decrementLikeCount(
        @Param("linkId") linkId: UUID,
        @Param("statDate") statDate: LocalDate,
    ): Int

    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(
        """
        UPDATE link_daily_stats
        SET save_count = save_count + 1, updated_at_utc = NOW()
        WHERE link_id = :linkId AND stat_date = :statDate
        """,
        nativeQuery = true,
    )
    fun incrementSaveCount(
        @Param("linkId") linkId: UUID,
        @Param("statDate") statDate: LocalDate,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE link_daily_stats
        SET save_count = save_count - 1, updated_at_utc = NOW()
        WHERE link_id = :linkId AND stat_date = :statDate
        """,
        nativeQuery = true,
    )
    fun decrementSaveCount(
        @Param("linkId") linkId: UUID,
        @Param("statDate") statDate: LocalDate,
    ): Int
}
