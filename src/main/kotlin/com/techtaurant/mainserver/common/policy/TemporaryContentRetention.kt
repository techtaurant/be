package com.techtaurant.mainserver.common.policy

/**
 * 임시저장 게시물과 확정되지 않은 임시 첨부의 공통 보관 기간.
 *
 * 두 정리 경로가 서로 다른 기준을 쓰면 게시물은 남아 있는데 첨부만 먼저 사라지거나
 * 그 반대로 주인 없는 첨부가 계속 쌓이는 상태가 생기므로 같은 값을 공유한다.
 * post와 attachment 어느 모듈도 이 정책을 단독으로 소유하지 않아 common에 둔다.
 */
object TemporaryContentRetention {
    const val DAYS = 14L
}
