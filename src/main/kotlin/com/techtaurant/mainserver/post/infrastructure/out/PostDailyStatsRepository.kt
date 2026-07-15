package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.post.entity.PostDailyStats
import org.springframework.data.repository.Repository
import java.time.LocalDate
import java.util.UUID

interface PostDailyStatsRepository : Repository<PostDailyStats, UUID>, PostDailyStatsRepositoryCustom {
    override fun save(stats: PostDailyStats): PostDailyStats

    override fun saveAll(stats: Iterable<PostDailyStats>): List<PostDailyStats>

    override fun deleteAllInBatch()

    override fun findAll(): List<PostDailyStats>

    override fun incrementViewCount(
        postId: UUID,
        statDate: LocalDate,
    ): Int

    override fun incrementLikeCount(
        postId: UUID,
        statDate: LocalDate,
    ): Int

    override fun decrementLikeCount(
        postId: UUID,
        statDate: LocalDate,
    ): Int

    override fun incrementCommentCount(
        postId: UUID,
        statDate: LocalDate,
    ): Int

    override fun decrementCommentCount(
        postId: UUID,
        statDate: LocalDate,
    ): Int
}
