package com.techtaurant.mainserver.post.infrastructure.out

import com.github.f4b6a3.uuid.UuidCreator
import com.techtaurant.mainserver.jooq.tables.PostDailyStats.Companion.POST_DAILY_STATS
import com.techtaurant.mainserver.jooq.tables.records.PostDailyStatsRecord
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.entity.PostDailyStats
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import org.jooq.DSLContext
import org.jooq.TableField
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@Repository
class PostDailyStatsRepositoryCustomImpl(
    private val dsl: DSLContext,
) : PostDailyStatsRepository {
    override fun save(stats: PostDailyStats): PostDailyStats {
        val id = stats.id ?: UuidCreator.getTimeOrderedEpoch().also { stats.id = it }
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        dsl.insertInto(POST_DAILY_STATS)
            .set(POST_DAILY_STATS.ID, id)
            .set(POST_DAILY_STATS.POST_ID, requireNotNull(stats.post.id))
            .set(POST_DAILY_STATS.STAT_DATE, stats.statDate)
            .set(POST_DAILY_STATS.VIEW_COUNT, stats.viewCount)
            .set(POST_DAILY_STATS.LIKE_COUNT, stats.likeCount)
            .set(POST_DAILY_STATS.COMMENT_COUNT, stats.commentCount)
            .set(POST_DAILY_STATS.CREATED_AT_UTC, stats.createdAt.atOffset(ZoneOffset.UTC))
            .set(POST_DAILY_STATS.UPDATED_AT_UTC, now)
            .onConflict(POST_DAILY_STATS.ID)
            .doUpdate()
            .set(POST_DAILY_STATS.VIEW_COUNT, stats.viewCount)
            .set(POST_DAILY_STATS.LIKE_COUNT, stats.likeCount)
            .set(POST_DAILY_STATS.COMMENT_COUNT, stats.commentCount)
            .set(POST_DAILY_STATS.UPDATED_AT_UTC, now)
            .execute()
        stats.updatedAt = now.toInstant()
        return stats
    }

    override fun saveAll(stats: Iterable<PostDailyStats>): List<PostDailyStats> = stats.map(::save)

    override fun deleteAllInBatch() {
        dsl.deleteFrom(POST_DAILY_STATS).execute()
    }

    override fun findAll(): List<PostDailyStats> = dsl.selectFrom(POST_DAILY_STATS).fetch().map { it.toPostDailyStats() }

    override fun insertIfAbsent(
        id: UUID,
        postId: UUID,
        statDate: LocalDate,
    ): Int {
        val now = Instant.now().atOffset(ZoneOffset.UTC)

        // 충돌 타깃은 실제 제약인 UNIQUE(post_id, stat_date)여야 한다.
        // PK(id)를 타깃으로 두면 매번 새 UUID라 충돌이 잡히지 않고 23505가 트랜잭션을 중단시킨다.
        return dsl.insertInto(POST_DAILY_STATS)
            .set(POST_DAILY_STATS.ID, id)
            .set(POST_DAILY_STATS.POST_ID, postId)
            .set(POST_DAILY_STATS.STAT_DATE, statDate)
            .set(POST_DAILY_STATS.VIEW_COUNT, 0L)
            .set(POST_DAILY_STATS.LIKE_COUNT, 0L)
            .set(POST_DAILY_STATS.COMMENT_COUNT, 0L)
            .set(POST_DAILY_STATS.CREATED_AT_UTC, now)
            .set(POST_DAILY_STATS.UPDATED_AT_UTC, now)
            .onConflict(POST_DAILY_STATS.POST_ID, POST_DAILY_STATS.STAT_DATE)
            .doNothing()
            .execute()
    }

    override fun incrementViewCount(
        postId: UUID,
        statDate: LocalDate,
    ): Int = increment(postId, statDate, POST_DAILY_STATS.VIEW_COUNT, 1L)

    override fun incrementLikeCount(
        postId: UUID,
        statDate: LocalDate,
    ): Int = increment(postId, statDate, POST_DAILY_STATS.LIKE_COUNT, 1L)

    override fun decrementLikeCount(
        postId: UUID,
        statDate: LocalDate,
    ): Int = increment(postId, statDate, POST_DAILY_STATS.LIKE_COUNT, -1L)

    override fun incrementCommentCount(
        postId: UUID,
        statDate: LocalDate,
    ): Int = increment(postId, statDate, POST_DAILY_STATS.COMMENT_COUNT, 1L)

    override fun decrementCommentCount(
        postId: UUID,
        statDate: LocalDate,
    ): Int = increment(postId, statDate, POST_DAILY_STATS.COMMENT_COUNT, -1L)

    private fun increment(
        postId: UUID,
        statDate: LocalDate,
        field: TableField<*, Long?>,
        amount: Long,
    ): Int =
        dsl.update(POST_DAILY_STATS)
            .set(field, field.plus(amount))
            .set(POST_DAILY_STATS.UPDATED_AT_UTC, Instant.now().atOffset(ZoneOffset.UTC))
            .where(POST_DAILY_STATS.POST_ID.eq(postId).and(POST_DAILY_STATS.STAT_DATE.eq(statDate)))
            .execute()

    private fun PostDailyStatsRecord.toPostDailyStats(): PostDailyStats =
        PostDailyStats(
            post = Post("", "", User("", "", OAuthProvider.GOOGLE, "", UserRole.USER, "")).apply { id = requireNotNull(postId) },
            statDate = requireNotNull(statDate),
            viewCount = requireNotNull(viewCount),
            likeCount = requireNotNull(likeCount),
            commentCount = requireNotNull(commentCount),
        ).apply {
            id = requireNotNull(this@toPostDailyStats.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }
}
