package com.techtaurant.mainserver.link.enums

enum class LinkCrawlRunStatus {
    /** 실행 중 실패한 링크가 없어 정상 완료된 상태 */
    COMPLETED,

    /** 아직 해소되지 않은 실패 잡이 남아 있는 상태 */
    UNRESOLVED,

    /** 실패 잡이 있었으나 재시도로 모두 해소된 상태 */
    RESOLVED,

    /** 배치 실행 자체가 완료되지 못하고 실패한 상태 */
    FAILED,
}
