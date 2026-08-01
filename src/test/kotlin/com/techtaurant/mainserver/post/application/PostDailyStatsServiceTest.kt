package com.techtaurant.mainserver.post.application

import com.techtaurant.mainserver.post.infrastructure.out.PostDailyStatsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class PostDailyStatsServiceTest {
    private val postDailyStatsRepository: PostDailyStatsRepository = mockk()

    private val postDailyStatsService = PostDailyStatsService(postDailyStatsRepository)

    private val postId = UUID.randomUUID()
    private val statDate = LocalDate.now(ZoneOffset.UTC)

    @Test
    @DisplayName("해당 일자 레코드가 있으면 증분 쿼리만 실행한다")
    fun incrementViewCount_withExistingDailyStats_runsChangeQueryOnly() {
        // given
        every { postDailyStatsRepository.incrementViewCount(postId, statDate) } returns 1

        // when
        postDailyStatsService.incrementViewCount(postId, statDate)

        // then
        verify(exactly = 1) { postDailyStatsRepository.incrementViewCount(postId, statDate) }
        verify(exactly = 0) { postDailyStatsRepository.insertIfAbsent(any(), any(), any()) }
    }

    @Test
    @DisplayName("해당 일자 레코드가 없으면 insertIfAbsent로 생성한 뒤 증분을 재시도한다")
    fun incrementViewCount_withoutDailyStats_insertsThenRetriesChange() {
        // given
        every { postDailyStatsRepository.incrementViewCount(postId, statDate) } returnsMany listOf(0, 1)
        every { postDailyStatsRepository.insertIfAbsent(any(), postId, statDate) } returns 1

        // when
        postDailyStatsService.incrementViewCount(postId, statDate)

        // then
        // 예외 기반 재시도는 23505로 트랜잭션을 중단시키므로, 충돌을 DB에서 무시하는 경로만 사용해야 한다
        verifyOrder {
            postDailyStatsRepository.incrementViewCount(postId, statDate)
            postDailyStatsRepository.insertIfAbsent(any(), postId, statDate)
            postDailyStatsRepository.incrementViewCount(postId, statDate)
        }
    }

    @Test
    @DisplayName("동시 생성으로 insertIfAbsent가 아무 행도 넣지 못해도 증분을 재시도한다")
    fun incrementCommentCount_whenInsertSkippedByConcurrentCreate_stillRetriesChange() {
        // given
        every { postDailyStatsRepository.incrementCommentCount(postId, statDate) } returnsMany listOf(0, 1)
        every { postDailyStatsRepository.insertIfAbsent(any(), postId, statDate) } returns 0

        // when
        postDailyStatsService.incrementCommentCount(postId, statDate)

        // then
        verify(exactly = 2) { postDailyStatsRepository.incrementCommentCount(postId, statDate) }
    }
}
