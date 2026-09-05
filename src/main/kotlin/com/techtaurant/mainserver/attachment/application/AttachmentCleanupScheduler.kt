package com.techtaurant.mainserver.attachment.application

import com.techtaurant.mainserver.common.policy.TemporaryContentRetention
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 보관 기간이 지난 미확정 첨부를 주기적으로 회수한다.
 *
 * 게시물 삭제와 orphan 정리는 referenceId로 첨부를 찾으므로, 어느 대상에도 소유가 기록되지 않은
 * 첨부는 그 경로들에 잡히지 않는다. 업로드만 하고 저장하지 않은 이미지가 여기에 해당한다.
 * 삭제가 멱등이라 여러 인스턴스가 동시에 실행해도 안전하다.
 */
@Component
class AttachmentCleanupScheduler(
    private val attachmentService: AttachmentService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val CLEANUP_INTERVAL_MILLIS = 3_600_000L

        // 한 번에 지우는 양을 제한해 이미 쌓인 물량이 많아도 트랜잭션과 S3 삭제 요청이 한꺼번에 몰리지 않게 한다.
        private const val MAX_DELETE_COUNT_PER_RUN = 500
    }

    @Scheduled(fixedDelay = CLEANUP_INTERVAL_MILLIS)
    fun deleteExpiredTmpAttachments() {
        val expirationThreshold = Instant.now().minus(TemporaryContentRetention.DAYS, ChronoUnit.DAYS)
        val deletedCount = attachmentService.deleteExpiredTmpAttachments(expirationThreshold, MAX_DELETE_COUNT_PER_RUN)

        if (deletedCount > 0) {
            log.info("Deleted {} expired tmp attachments created before {}", deletedCount, expirationThreshold)
        }
    }
}
