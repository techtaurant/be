package com.techtaurant.mainserver.notification.infrastructure.out

import com.techtaurant.mainserver.jooq.tables.NotificationArguments.Companion.NOTIFICATION_ARGUMENTS
import com.techtaurant.mainserver.jooq.tables.Notifications.Companion.NOTIFICATIONS
import com.techtaurant.mainserver.jooq.tables.records.NotificationsRecord
import com.techtaurant.mainserver.notification.entity.Notification
import com.techtaurant.mainserver.notification.entity.NotificationArgument
import com.techtaurant.mainserver.notification.enums.NotificationTargetType
import com.techtaurant.mainserver.notification.enums.NotificationType
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class NotificationArgumentRepositoryCustomImpl(
    private val dsl: DSLContext,
) : NotificationArgumentRepository {
    override fun findAllByNotificationIdOrderByCreatedAtAsc(notificationId: UUID): List<NotificationArgument> =
        fetchArguments(NOTIFICATION_ARGUMENTS.NOTIFICATION_ID.eq(notificationId))

    override fun findAllByNotificationIdInOrderByCreatedAtAsc(notificationIds: Collection<UUID>): List<NotificationArgument> =
        if (notificationIds.isEmpty()) emptyList() else fetchArguments(NOTIFICATION_ARGUMENTS.NOTIFICATION_ID.`in`(notificationIds))

    private fun fetchArguments(condition: Condition): List<NotificationArgument> =
        dsl.select(NOTIFICATION_ARGUMENTS.asterisk(), NOTIFICATIONS.asterisk())
            .from(NOTIFICATION_ARGUMENTS)
            .join(NOTIFICATIONS)
            .on(NOTIFICATION_ARGUMENTS.NOTIFICATION_ID.eq(NOTIFICATIONS.ID))
            .where(condition)
            .orderBy(NOTIFICATION_ARGUMENTS.CREATED_AT_UTC.asc())
            .fetch()
            .map(::toArgument)

    private fun toArgument(record: Record): NotificationArgument {
        val argumentRecord = record.into(NOTIFICATION_ARGUMENTS)
        return NotificationArgument(
            notification = record.into(NOTIFICATIONS).toNotification(),
            targetType = NotificationTargetType.valueOf(requireNotNull(argumentRecord.targetType).toString()),
            targetId = requireNotNull(argumentRecord.targetId),
        ).apply {
            id = requireNotNull(argumentRecord.id)
            createdAt = requireNotNull(argumentRecord.createdAtUtc).toInstant()
            updatedAt = requireNotNull(argumentRecord.updatedAtUtc).toInstant()
        }
    }

    private fun NotificationsRecord.toNotification(): Notification =
        Notification(NotificationType.valueOf(requireNotNull(type).toString())).apply {
            id = requireNotNull(this@toNotification.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }
}
