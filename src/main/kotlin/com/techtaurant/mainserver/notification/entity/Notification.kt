package com.techtaurant.mainserver.notification.entity

import com.techtaurant.mainserver.common.base.EntityBase
import com.techtaurant.mainserver.notification.enums.NotificationType

class Notification(
    var type: NotificationType,
    var arguments: MutableList<NotificationArgument> = mutableListOf(),
    var recipients: MutableList<NotificationRecipient> = mutableListOf(),
) : EntityBase() {
    fun addArgument(argument: NotificationArgument) {
        arguments.add(argument)
    }

    fun addRecipient(recipient: NotificationRecipient) {
        recipients.add(recipient)
    }
}
