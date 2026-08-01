package com.techtaurant.mainserver.post.application

import com.github.f4b6a3.uuid.UuidCreator
import com.techtaurant.mainserver.post.infrastructure.out.PostDailyStatsRepository
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

/**
 * 일별 게시물 통계 증분 서비스
 * 이벤트 발생 시 PostDailyStats 레코드를 생성하거나 원자적으로 증분합니다.
 * 레코드 생성은 UNIQUE(post_id, stat_date) 충돌을 DB에서 무시하므로 동시 요청에도 트랜잭션이 유지됩니다.
 */
@Service
class PostDailyStatsService(
    private val postDailyStatsRepository: PostDailyStatsRepository,
) {
    /**
     * 일별 조회수를 원자적으로 1 증가시킵니다.
     *
     * @param postId 게시물 ID
     */
    fun incrementViewCount(
        postId: UUID,
        statDate: LocalDate,
    ) {
        applyDailyStatsChange(postId, statDate, postDailyStatsRepository::incrementViewCount)
    }

    /**
     * 일별 좋아요수를 원자적으로 1 증가시킵니다.
     *
     * @param postId 게시물 ID
     */
    fun incrementLikeCount(
        postId: UUID,
        statDate: LocalDate,
    ) {
        applyDailyStatsChange(postId, statDate, postDailyStatsRepository::incrementLikeCount)
    }

    /**
     * 일별 좋아요수를 원자적으로 1 감소시킵니다.
     * 레코드가 없으면 생성 후 감소를 재시도하여 음수 값을 가질 수 있습니다.
     *
     * @param postId 게시물 ID
     */
    fun decrementLikeCount(
        postId: UUID,
        statDate: LocalDate,
    ) {
        applyDailyStatsChange(postId, statDate, postDailyStatsRepository::decrementLikeCount)
    }

    /**
     * 일별 댓글수를 원자적으로 1 증가시킵니다.
     *
     * @param postId 게시물 ID
     */
    fun incrementCommentCount(
        postId: UUID,
        statDate: LocalDate,
    ) {
        applyDailyStatsChange(postId, statDate, postDailyStatsRepository::incrementCommentCount)
    }

    /**
     * 지정한 일자의 댓글수를 원자적으로 1 감소시킵니다.
     * 레코드가 없으면 생성 후 감소를 재시도하여 음수 값을 가질 수 있습니다.
     *
     * @param postId 게시물 ID
     * @param statDate 통계 일자
     */
    fun decrementCommentCount(
        postId: UUID,
        statDate: LocalDate,
    ) {
        applyDailyStatsChange(postId, statDate, postDailyStatsRepository::decrementCommentCount)
    }

    private fun applyDailyStatsChange(
        postId: UUID,
        statDate: LocalDate,
        changeFn: (UUID, LocalDate) -> Int,
    ) {
        if (changeFn(postId, statDate) == 0) {
            postDailyStatsRepository.insertIfAbsent(
                id = UuidCreator.getTimeOrderedEpoch(),
                postId = postId,
                statDate = statDate,
            )
            changeFn(postId, statDate)
        }
    }
}
