package com.techtaurant.mainserver.notification.infrastructure.out

import com.techtaurant.mainserver.notification.entity.Notification
import com.techtaurant.mainserver.notification.enums.NotificationTargetType
import com.techtaurant.mainserver.notification.enums.NotificationType
import java.util.UUID

interface NotificationRepositoryCustom {
    fun findAllByTypeAndActorAndTarget(
        type: NotificationType,
        actorUserId: UUID,
        targetType: NotificationTargetType,
        targetId: UUID,
        actorTargetType: NotificationTargetType = NotificationTargetType.USER,
    ): List<Notification>
}
