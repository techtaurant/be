package com.techtaurant.mainserver.link.enums

enum class LinkCrawlRunTriggerType {
    /** 스케줄러가 cron 조건으로 실행 */
    SCHEDULED,

    /** 관리자가 수동으로 실행 */
    MANUAL,
}
