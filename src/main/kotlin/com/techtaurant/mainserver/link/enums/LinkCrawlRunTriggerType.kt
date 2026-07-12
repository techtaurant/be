package com.techtaurant.mainserver.link.enums

enum class LinkCrawlRunTriggerType {
    /** 배치 생성 직후 최초 수집 */
    CREATED,

    /** 스케줄러가 cron 조건으로 실행 */
    SCHEDULED,

    /** 관리자가 수동으로 실행 */
    MANUAL,
}
