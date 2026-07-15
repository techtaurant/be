package com.techtaurant.mainserver.notification.infrastructure.out

import com.github.f4b6a3.uuid.UuidCreator
import com.techtaurant.mainserver.jooq.tables.NotificationArguments.Companion.NOTIFICATION_ARGUMENTS
import com.techtaurant.mainserver.jooq.tables.NotificationRecipients.Companion.NOTIFICATION_RECIPIENTS
import com.techtaurant.mainserver.jooq.tables.Notifications.Companion.NOTIFICATIONS
import com.techtaurant.mainserver.jooq.tables.records.NotificationsRecord
import com.techtaurant.mainserver.notification.entity.Notification
import com.techtaurant.mainserver.notification.enums.NotificationTargetType
import com.techtaurant.mainserver.notification.enums.NotificationType
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

@Repository
class NotificationRepositoryCustomImpl(
    private val dsl: DSLContext,
) : NotificationRepository {
    override fun save(notification: Notification): Notification {
        val id = notification.id ?: UuidCreator.getTimeOrderedEpoch().also { notification.id = it }
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        dsl.insertInto(NOTIFICATIONS)
            .set(NOTIFICATIONS.ID, id).set(NOTIFICATIONS.TYPE, enumValue("notification_type", notification.type.name))
            .set(NOTIFICATIONS.CREATED_AT_UTC, notification.createdAt.atOffset(ZoneOffset.UTC)).set(NOTIFICATIONS.UPDATED_AT_UTC, now)
            .onConflict(
                NOTIFICATIONS.ID,
            ).doUpdate().set(
                NOTIFICATIONS.TYPE,
                enumValue("notification_type", notification.type.name),
            ).set(NOTIFICATIONS.UPDATED_AT_UTC, now).execute()
        notification.arguments.forEach { argument ->
            val argumentId = argument.id ?: UuidCreator.getTimeOrderedEpoch().also { argument.id = it }
            dsl.insertInto(NOTIFICATION_ARGUMENTS)
                .set(NOTIFICATION_ARGUMENTS.ID, argumentId).set(NOTIFICATION_ARGUMENTS.NOTIFICATION_ID, id)
                .set(
                    NOTIFICATION_ARGUMENTS.TARGET_TYPE,
                    enumValue("notification_target_type", argument.targetType.name),
                ).set(NOTIFICATION_ARGUMENTS.TARGET_ID, argument.targetId)
                .set(
                    NOTIFICATION_ARGUMENTS.CREATED_AT_UTC,
                    argument.createdAt.atOffset(ZoneOffset.UTC),
                ).set(NOTIFICATION_ARGUMENTS.UPDATED_AT_UTC, now)
                .onConflict(NOTIFICATION_ARGUMENTS.ID)
                .doUpdate()
                .set(NOTIFICATION_ARGUMENTS.TARGET_TYPE, enumValue("notification_target_type", argument.targetType.name))
                .set(NOTIFICATION_ARGUMENTS.TARGET_ID, argument.targetId)
                .set(NOTIFICATION_ARGUMENTS.UPDATED_AT_UTC, now)
                .execute()
            argument.updatedAt = now.toInstant()
        }
        notification.recipients.forEach { recipient ->
            val recipientId = recipient.id ?: UuidCreator.getTimeOrderedEpoch().also { recipient.id = it }
            dsl.insertInto(NOTIFICATION_RECIPIENTS)
                .set(NOTIFICATION_RECIPIENTS.ID, recipientId).set(NOTIFICATION_RECIPIENTS.NOTIFICATION_ID, id)
                .set(
                    NOTIFICATION_RECIPIENTS.USER_ID,
                    requireNotNull(recipient.recipientUser.id),
                ).set(NOTIFICATION_RECIPIENTS.READ_AT_UTC, recipient.readAt?.atOffset(ZoneOffset.UTC))
                .set(
                    NOTIFICATION_RECIPIENTS.CREATED_AT_UTC,
                    recipient.createdAt.atOffset(ZoneOffset.UTC),
                ).set(NOTIFICATION_RECIPIENTS.UPDATED_AT_UTC, now)
                .onConflict(NOTIFICATION_RECIPIENTS.ID)
                .doUpdate()
                .set(NOTIFICATION_RECIPIENTS.READ_AT_UTC, recipient.readAt?.atOffset(ZoneOffset.UTC))
                .set(NOTIFICATION_RECIPIENTS.UPDATED_AT_UTC, now)
                .execute()
            recipient.updatedAt = now.toInstant()
        }
        notification.updatedAt = now.toInstant()
        return notification
    }

    override fun deleteAll(notifications: Iterable<Notification>) {
        val ids = notifications.mapNotNull(Notification::id)
        if (ids.isNotEmpty()) dsl.deleteFrom(NOTIFICATIONS).where(NOTIFICATIONS.ID.`in`(ids)).execute()
    }

    override fun findAll(): List<Notification> = dsl.selectFrom(NOTIFICATIONS).fetch().map { it.toNotification() }

    override fun findById(id: UUID): Optional<Notification> =
        Optional.ofNullable(dsl.selectFrom(NOTIFICATIONS).where(NOTIFICATIONS.ID.eq(id)).fetchOne()?.toNotification())

    private fun enumValue(
        type: String,
        value: String,
    ) = DSL.field("cast({0} as $type)", String::class.java, DSL.value(value))

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
