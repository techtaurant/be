package com.techtaurant.mainserver.post.application

import com.techtaurant.mainserver.attachment.application.AttachmentService
import com.techtaurant.mainserver.common.policy.TemporaryContentRetention
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 보관 기간이 지난 임시 콘텐츠를 주기적으로 회수한다.
 *
 * 만료된 임시저장과 그 첨부, 그리고 어느 대상에도 소유가 기록되지 않은 만료 첨부를 한 번의 실행에서
 * 함께 정리한다. 두 대상은 정리 주체가 서로 달라서 따로 두면 한쪽만 사라지는 구간이 생긴다.
 * 임시저장 목록 조회도 같은 정리를 수행하지만 목록을 열지 않는 사용자의 임시저장은 그 경로에 잡히지
 * 않으므로, 그런 임시저장을 회수하는 주체는 이 배치뿐이다.
 *
 * 한 번의 실행에서 지울 대상이 없어질 때까지 여러 배치로 나눠 반복하되 반복 횟수에 상한이 있어,
 * 밀린 물량이 아주 많으면 여러 날에 걸쳐 회수된다. 보관 기간이 지나는 즉시 사라지는 것은 아니다.
 *
 * 삭제가 멱등이라 여러 인스턴스가 동시에 실행해도 안전하다.
 */
@Component
class TemporaryContentCleanupScheduler(
    private val expiredDraftCleanupService: ExpiredDraftCleanupService,
    private val attachmentService: AttachmentService,
    private val temporaryContentRetention: TemporaryContentRetention,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val CLEANUP_INTERVAL_MILLIS = 86_400_000L

        // 한 배치가 지우는 양을 제한해 이미 쌓인 물량이 많아도 트랜잭션과 S3 삭제 요청이 한꺼번에 몰리지 않게 한다.
        // 임시저장은 건당 첨부 조회와 S3 삭제가 따라붙어 첨부 단독 정리보다 무거우므로 상한을 낮게 잡는다.
        private const val DRAFT_DELETE_BATCH_SIZE = 100
        private const val ATTACHMENT_DELETE_BATCH_SIZE = 500

        // 배치를 반복해 밀린 물량을 회수하되, 한 실행이 무한히 늘어지지 않도록 반복 횟수를 제한한다.
        private const val MAX_BATCHES_PER_RUN = 20
    }

    @Scheduled(fixedDelay = CLEANUP_INTERVAL_MILLIS)
    fun deleteExpiredTemporaryContent() {
        val expirationThreshold = temporaryContentRetention.expirationThreshold()

        // 임시저장을 먼저 지워야 그 첨부가 여기서 회수되고, 남은 미확정 첨부만 다음 단계가 훑는다.
        val deletedDraftCount =
            deleteUntilDrained(DRAFT_DELETE_BATCH_SIZE) { batchSize ->
                expiredDraftCleanupService.deleteExpiredDrafts(batchSize, null)
            }
        val deletedAttachmentCount =
            deleteUntilDrained(ATTACHMENT_DELETE_BATCH_SIZE) { batchSize ->
                attachmentService.deleteExpiredTmpAttachments(expirationThreshold, batchSize)
            }

        if (deletedDraftCount > 0 || deletedAttachmentCount > 0) {
            log.info(
                "Deleted {} expired drafts and {} expired tmp attachments older than {}",
                deletedDraftCount,
                deletedAttachmentCount,
                expirationThreshold,
            )
        }
    }

    /**
     * 지울 대상이 남지 않을 때까지 [batchSize]건씩 삭제를 반복하고 총 삭제 건수를 돌려줍니다.
     *
     * 삭제 건수가 [batchSize]보다 적으면 그 배치가 대상을 모두 훑었다는 뜻이므로 멈춥니다.
     * 지우지 못한 대상을 남기고 돌아온 배치도 같은 조건에 걸려 멈추는데, 삭제가 실패하는 동안 남은 물량까지
     * 계속 훑기보다 이번 실행을 접고 다음 실행에 맡기는 편이 안전합니다.
     * [MAX_BATCHES_PER_RUN]에 걸려 멈춘 잔량은 다음 실행이 이어서 회수합니다.
     *
     * @param batchSize 한 배치가 지울 최대 건수
     * @param deleteBatch 한 배치를 삭제하고 삭제 건수를 돌려주는 동작
     * @return 이번 실행에서 삭제한 총 건수
     */
    private fun deleteUntilDrained(
        batchSize: Int,
        deleteBatch: (Int) -> Int,
    ): Int {
        var totalDeletedCount = 0

        repeat(MAX_BATCHES_PER_RUN) {
            val deletedCount = deleteBatch(batchSize)
            totalDeletedCount += deletedCount

            if (deletedCount < batchSize) return totalDeletedCount
        }

        return totalDeletedCount
    }
}
