package com.techtaurant.mainserver.notification.enums

import com.techtaurant.mainserver.common.status.StatusIfs
import org.springframework.http.HttpStatus

enum class NotificationStatus(
    private val httpStatusCode: Int,
    private val customStatusCode: Int,
    private val description: String,
) : StatusIfs {
    NOTIFICATION_PAYLOAD_REQUIRED(HttpStatus.BAD_REQUEST.value(), 8001, "알림 payload는 비어 있을 수 없습니다"),
    NOTIFICATION_RECIPIENT_REQUIRED(HttpStatus.BAD_REQUEST.value(), 8002, "수신자는 최소 1명 이상이어야 합니다"),
    NOTIFICATION_ARGUMENT_REQUIRED(HttpStatus.BAD_REQUEST.value(), 8003, "알림 메시지 인자 정보가 필요합니다"),
    ;

    override fun getHttpStatusCode(): Int {
        return httpStatusCode
    }

    override fun getCustomStatusCode(): Int {
        return customStatusCode
    }

    override fun getDescription(): String {
        return description
    }
}
