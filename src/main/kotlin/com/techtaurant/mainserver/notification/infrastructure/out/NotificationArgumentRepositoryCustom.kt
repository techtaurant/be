package com.techtaurant.mainserver.notification.infrastructure.out

import com.techtaurant.mainserver.notification.entity.NotificationArgument
import java.util.UUID

interface NotificationArgumentRepositoryCustom {
    fun findAllByNotificationIdOrderByCreatedAtAsc(notificationId: UUID): List<NotificationArgument>

    fun findAllByNotificationIdInOrderByCreatedAtAsc(notificationIds: Collection<UUID>): List<NotificationArgument>
}
