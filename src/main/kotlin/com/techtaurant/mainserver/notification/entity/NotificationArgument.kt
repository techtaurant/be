package com.techtaurant.mainserver.notification.entity

import com.techtaurant.mainserver.common.base.EntityBase
import com.techtaurant.mainserver.notification.enums.NotificationTargetType
import java.util.UUID

class NotificationArgument(
    var notification: Notification,
    var targetType: NotificationTargetType,
    var targetId: UUID,
) : EntityBase()
