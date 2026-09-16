package com.techtaurant.mainserver.link.enums

enum class LinkCrawlRunStatus {
    /** 실행 중 실패한 링크가 없어 정상 완료된 상태 */
    COMPLETED,

    /** 이 실행에 연결된 미해소 실패 잡이 남아 있는 상태 */
    UNRESOLVED,

    /** 실패 잡이 이 실행에 연결된 채로 재시도, 정기 실행, 링크 직접 등록 중 하나로 모두 해소된 상태 */
    RESOLVED,

    /**
     * 미해소 실패 잡이 같은 URL을 다시 실패시킨 이후 실행으로 넘어가, 이 실행에서는 더 처리할 잡이 없는 상태.
     * 넘어간 잡이 나중에 해소돼도 이 실행과의 연결이 없으므로 이 상태로 남는다.
     */
    CARRIED_OVER,

    /** 배치 실행 자체가 완료되지 못하고 실패한 상태 */
    FAILED,
}
