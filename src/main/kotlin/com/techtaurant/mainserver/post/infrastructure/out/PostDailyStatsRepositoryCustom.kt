package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.post.entity.PostDailyStats
import java.time.LocalDate
import java.util.UUID

interface PostDailyStatsRepositoryCustom {
    fun save(stats: PostDailyStats): PostDailyStats

    fun saveAll(stats: Iterable<PostDailyStats>): List<PostDailyStats>

    fun deleteAllInBatch()

    fun findAll(): List<PostDailyStats>

    fun incrementViewCount(
        postId: UUID,
        statDate: LocalDate,
    ): Int

    fun incrementLikeCount(
        postId: UUID,
        statDate: LocalDate,
    ): Int

    fun decrementLikeCount(
        postId: UUID,
        statDate: LocalDate,
    ): Int

    fun incrementCommentCount(
        postId: UUID,
        statDate: LocalDate,
    ): Int

    fun decrementCommentCount(
        postId: UUID,
        statDate: LocalDate,
    ): Int
}
