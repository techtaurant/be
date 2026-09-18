package com.techtaurant.mainserver.post.application

import com.techtaurant.mainserver.attachment.application.AttachmentService
import com.techtaurant.mainserver.common.policy.TemporaryContentRetention
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("임시 콘텐츠 정리 배치 단위 테스트")
class TemporaryContentCleanupSchedulerTest {
    /**
     * 배치가 한 번에 지우는 건수와 반복 상한. 스케줄러의 private 상수와 같은 값을 씁니다.
     * 밀린 물량이 회당 상한을 넘을 때 다음 배치를 이어서 도는지가 이 테스트의 관심사입니다.
     */
    private val draftBatchSize = 100
    private val attachmentBatchSize = 500
    private val maxBatchesPerRun = 20

    private val expiredDraftCleanupService: ExpiredDraftCleanupService = mockk()
    private val attachmentService: AttachmentService = mockk()

    private val scheduler =
        TemporaryContentCleanupScheduler(
            expiredDraftCleanupService = expiredDraftCleanupService,
            attachmentService = attachmentService,
            temporaryContentRetention = TemporaryContentRetention(retentionDays = 14),
        )

    @Test
    @DisplayName("배치 상한만큼 지워지면 남은 물량을 같은 실행에서 이어서 지운다")
    fun deleteExpiredTemporaryContent_backlogExceedsBatchSize_repeatsUntilDrained() {
        // given - 임시저장은 두 배치, 첨부는 세 배치에 걸쳐 있는 물량
        every { expiredDraftCleanupService.deleteExpiredDrafts(draftBatchSize, null) } returnsMany
            listOf(draftBatchSize, 30)
        every { attachmentService.deleteExpiredTmpAttachments(any(), attachmentBatchSize) } returnsMany
            listOf(attachmentBatchSize, attachmentBatchSize, 7)

        // when
        scheduler.deleteExpiredTemporaryContent()

        // then
        verify(exactly = 2) { expiredDraftCleanupService.deleteExpiredDrafts(draftBatchSize, null) }
        verify(exactly = 3) { attachmentService.deleteExpiredTmpAttachments(any(), attachmentBatchSize) }
    }

    @Test
    @DisplayName("배치 상한보다 적게 지워지면 그 배치에서 멈춘다")
    fun deleteExpiredTemporaryContent_backlogWithinBatchSize_runsSingleBatch() {
        // given
        every { expiredDraftCleanupService.deleteExpiredDrafts(draftBatchSize, null) } returns 0
        every { attachmentService.deleteExpiredTmpAttachments(any(), attachmentBatchSize) } returns 3

        // when
        scheduler.deleteExpiredTemporaryContent()

        // then
        verify(exactly = 1) { expiredDraftCleanupService.deleteExpiredDrafts(draftBatchSize, null) }
        verify(exactly = 1) { attachmentService.deleteExpiredTmpAttachments(any(), attachmentBatchSize) }
    }

    @Test
    @DisplayName("물량이 계속 남아 있어도 한 실행의 반복 횟수는 상한을 넘지 않는다")
    fun deleteExpiredTemporaryContent_backlogNeverDrains_stopsAtIterationLimit() {
        // given - 매 배치가 상한만큼 지워 잔량이 끝나지 않는 상태
        every { expiredDraftCleanupService.deleteExpiredDrafts(draftBatchSize, null) } returns draftBatchSize
        every { attachmentService.deleteExpiredTmpAttachments(any(), attachmentBatchSize) } returns attachmentBatchSize

        // when
        scheduler.deleteExpiredTemporaryContent()

        // then - 남은 물량은 다음 실행이 이어서 회수한다
        verify(exactly = maxBatchesPerRun) { expiredDraftCleanupService.deleteExpiredDrafts(draftBatchSize, null) }
        verify(exactly = maxBatchesPerRun) { attachmentService.deleteExpiredTmpAttachments(any(), attachmentBatchSize) }
    }
}
