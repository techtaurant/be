package com.techtaurant.mainserver.common.policy

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 임시저장 게시물과 확정되지 않은 임시 첨부의 공통 보관 기간.
 *
 * 두 정리 경로가 서로 다른 기준을 쓰면 게시물은 남아 있는데 첨부만 먼저 사라지거나
 * 그 반대로 주인 없는 첨부가 계속 쌓이는 상태가 생기므로 같은 값을 공유한다.
 * post와 attachment 어느 모듈도 이 정책을 단독으로 소유하지 않아 common에 둔다.
 *
 * 기간은 S3 tmp/ lifecycle 규칙과 같은 SSM 파라미터에서 내려오므로, 인프라와 서버가
 * 각자 숫자를 들고 있다가 한쪽만 바뀌어 조용히 어긋나는 일이 없다.
 */
@Component
class TemporaryContentRetention(
    @param:Value("\${aws.s3.tmp-retention-days}")
    private val retentionDays: Long,
) {
    /**
     * 이 시각 이전에 만들어졌거나 수정된 임시 콘텐츠가 정리 대상입니다.
     */
    fun expirationThreshold(): Instant = Instant.now().minus(retentionDays, ChronoUnit.DAYS)
}
