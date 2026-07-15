package com.techtaurant.mainserver.notification.entity

import com.techtaurant.mainserver.common.base.EntityBase
import com.techtaurant.mainserver.user.entity.User
import java.time.Instant

class NotificationRecipient(
    var notification: Notification,
    var recipientUser: User,
    var readAt: Instant? = null,
) : EntityBase() {
    fun markAsRead(readAt: Instant) {
        if (this.readAt == null) {
            this.readAt = readAt
        }
    }
}
