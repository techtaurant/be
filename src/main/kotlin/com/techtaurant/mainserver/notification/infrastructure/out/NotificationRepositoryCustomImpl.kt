package com.techtaurant.mainserver.notification.infrastructure.out

import com.techtaurant.mainserver.jooq.tables.NotificationArguments.Companion.NOTIFICATION_ARGUMENTS
import com.techtaurant.mainserver.jooq.tables.Notifications.Companion.NOTIFICATIONS
import com.techtaurant.mainserver.jooq.tables.records.NotificationsRecord
import com.techtaurant.mainserver.notification.entity.Notification
import com.techtaurant.mainserver.notification.enums.NotificationTargetType
import com.techtaurant.mainserver.notification.enums.NotificationType
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class NotificationRepositoryCustomImpl(
    private val dsl: DSLContext,
) : NotificationRepositoryCustom {
    override fun findAllByTypeAndActorAndTarget(
        type: NotificationType,
        actorUserId: UUID,
        targetType: NotificationTargetType,
        targetId: UUID,
        actorTargetType: NotificationTargetType,
    ): List<Notification> {
        val actor = NOTIFICATION_ARGUMENTS.`as`("actor")
        val target = NOTIFICATION_ARGUMENTS.`as`("target")

        return dsl.selectDistinct(NOTIFICATIONS.fields().toList())
            .from(NOTIFICATIONS)
            .join(actor).on(actor.NOTIFICATION_ID.eq(NOTIFICATIONS.ID))
            .join(target).on(target.NOTIFICATION_ID.eq(NOTIFICATIONS.ID))
            .where(
                NOTIFICATIONS.TYPE.cast(String::class.java).eq(type.name)
                    .and(actor.TARGET_TYPE.cast(String::class.java).eq(actorTargetType.name))
                    .and(actor.TARGET_ID.eq(actorUserId))
                    .and(target.TARGET_TYPE.cast(String::class.java).eq(targetType.name))
                    .and(target.TARGET_ID.eq(targetId)),
            ).fetch()
            .map { it.into(NOTIFICATIONS).toNotification() }
    }

    private fun NotificationsRecord.toNotification(): Notification =
        Notification(NotificationType.valueOf(requireNotNull(type).toString())).apply {
            id = requireNotNull(this@toNotification.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }
}
