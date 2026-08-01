package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.post.entity.PostDailyStats
import java.time.LocalDate
import java.util.UUID

interface PostDailyStatsRepositoryCustom {
    fun save(stats: PostDailyStats): PostDailyStats

    fun saveAll(stats: Iterable<PostDailyStats>): List<PostDailyStats>

    fun deleteAllInBatch()

    fun findAll(): List<PostDailyStats>

    /**
     * 해당 게시물/일자의 통계 레코드가 없을 때만 0으로 초기화된 행을 넣습니다.
     * 동시 요청이 겹쳐도 UNIQUE(post_id, stat_date) 충돌을 DB에서 무시하므로 트랜잭션이 중단되지 않습니다.
     *
     * @param id 새로 생성할 통계 레코드 PK
     * @param postId 게시물 ID
     * @param statDate 통계 일자
     * @return 실제로 삽입된 행 수 (이미 존재하면 0)
     */
    fun insertIfAbsent(
        id: UUID,
        postId: UUID,
        statDate: LocalDate,
    ): Int

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
