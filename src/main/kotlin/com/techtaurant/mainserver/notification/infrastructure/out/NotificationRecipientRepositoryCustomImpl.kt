package com.techtaurant.mainserver.notification.infrastructure.out

import com.techtaurant.mainserver.jooq.tables.NotificationRecipients.Companion.NOTIFICATION_RECIPIENTS
import com.techtaurant.mainserver.jooq.tables.Notifications.Companion.NOTIFICATIONS
import com.techtaurant.mainserver.jooq.tables.records.NotificationsRecord
import com.techtaurant.mainserver.notification.entity.Notification
import com.techtaurant.mainserver.notification.entity.NotificationRecipient
import com.techtaurant.mainserver.notification.enums.NotificationType
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Repository
class NotificationRecipientRepositoryCustomImpl(
    private val dsl: DSLContext,
) : NotificationRecipientRepositoryCustom {
    override fun findAllByNotificationIdOrderByCreatedAtAsc(notificationId: UUID): List<NotificationRecipient> =
        fetchRecipients(NOTIFICATION_RECIPIENTS.NOTIFICATION_ID.eq(notificationId), null, NOTIFICATION_RECIPIENTS.CREATED_AT_UTC.asc())

    override fun findAllByRecipientUserIdOrderByCreatedAtDescIdDesc(
        userId: UUID,
        pageable: Pageable,
    ): List<NotificationRecipient> =
        fetchRecipients(
            NOTIFICATION_RECIPIENTS.USER_ID.eq(userId),
            pageable.pageSize,
            NOTIFICATION_RECIPIENTS.CREATED_AT_UTC.desc(),
            NOTIFICATION_RECIPIENTS.ID.desc(),
        )

    override fun findPageByUserIdAndCursor(
        userId: UUID,
        cursorCreatedAt: Instant,
        cursorId: UUID,
        pageable: Pageable,
    ): List<NotificationRecipient> {
        val cursor = cursorCreatedAt.atOffset(ZoneOffset.UTC)
        val afterCursor =
            NOTIFICATION_RECIPIENTS.CREATED_AT_UTC.lt(cursor)
                .or(NOTIFICATION_RECIPIENTS.CREATED_AT_UTC.eq(cursor).and(NOTIFICATION_RECIPIENTS.ID.lt(cursorId)))
        return fetchRecipients(
            NOTIFICATION_RECIPIENTS.USER_ID.eq(userId).and(afterCursor),
            pageable.pageSize,
            NOTIFICATION_RECIPIENTS.CREATED_AT_UTC.desc(),
            NOTIFICATION_RECIPIENTS.ID.desc(),
        )
    }

    override fun findAllByRecipientUserIdAndNotificationIdInAndReadAtIsNull(
        userId: UUID,
        notificationIds: Collection<UUID>,
    ): List<NotificationRecipient> =
        if (notificationIds.isEmpty()) {
            emptyList()
        } else {
            fetchRecipients(
                NOTIFICATION_RECIPIENTS.USER_ID.eq(
                    userId,
                ).and(NOTIFICATION_RECIPIENTS.NOTIFICATION_ID.`in`(notificationIds)).and(NOTIFICATION_RECIPIENTS.READ_AT_UTC.isNull()),
            )
        }

    override fun countByRecipientUserIdAndReadAtIsNull(userId: UUID): Long =
        dsl.fetchCount(
            NOTIFICATION_RECIPIENTS,
            NOTIFICATION_RECIPIENTS.USER_ID.eq(userId).and(NOTIFICATION_RECIPIENTS.READ_AT_UTC.isNull()),
        ).toLong()

    private fun fetchRecipients(
        condition: Condition,
        limit: Int? = null,
        vararg orderBy: org.jooq.SortField<*>,
    ): List<NotificationRecipient> {
        val query =
            dsl.select(NOTIFICATION_RECIPIENTS.asterisk(), NOTIFICATIONS.asterisk())
                .from(NOTIFICATION_RECIPIENTS)
                .join(NOTIFICATIONS)
                .on(NOTIFICATION_RECIPIENTS.NOTIFICATION_ID.eq(NOTIFICATIONS.ID))
                .where(condition)
                .orderBy(orderBy.asList())
        return (limit?.let(query::limit) ?: query).fetch().map(::toRecipient)
    }

    private fun toRecipient(record: Record): NotificationRecipient {
        val recipient = record.into(NOTIFICATION_RECIPIENTS)
        return NotificationRecipient(
            notification = record.into(NOTIFICATIONS).toNotification(),
            recipientUser = userReference(requireNotNull(recipient.userId)),
            readAt = recipient.readAtUtc?.toInstant(),
        ).apply {
            id = requireNotNull(recipient.id)
            createdAt = requireNotNull(recipient.createdAtUtc).toInstant()
            updatedAt = requireNotNull(recipient.updatedAtUtc).toInstant()
        }
    }

    private fun NotificationsRecord.toNotification(): Notification =
        Notification(NotificationType.valueOf(requireNotNull(type).toString())).apply {
            id = requireNotNull(this@toNotification.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }

    private fun userReference(userId: UUID): User = User("", "", OAuthProvider.GOOGLE, "", UserRole.USER, "").apply { id = userId }
}
