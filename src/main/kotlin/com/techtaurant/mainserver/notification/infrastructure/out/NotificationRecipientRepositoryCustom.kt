package com.techtaurant.mainserver.notification.infrastructure.out

import com.techtaurant.mainserver.notification.entity.NotificationRecipient
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.util.UUID

interface NotificationRecipientRepositoryCustom {
    fun save(recipient: NotificationRecipient): NotificationRecipient

    fun saveAll(recipients: Iterable<NotificationRecipient>): List<NotificationRecipient>

    fun findById(id: UUID): java.util.Optional<NotificationRecipient>

    fun findAllByNotificationIdOrderByCreatedAtAsc(notificationId: UUID): List<NotificationRecipient>

    fun findAllByRecipientUserIdOrderByCreatedAtDescIdDesc(
        userId: UUID,
        pageable: Pageable,
    ): List<NotificationRecipient>

    fun findPageByUserIdAndCursor(
        userId: UUID,
        cursorCreatedAt: Instant,
        cursorId: UUID,
        pageable: Pageable,
    ): List<NotificationRecipient>

    fun findAllByRecipientUserIdAndNotificationIdInAndReadAtIsNull(
        userId: UUID,
        notificationIds: Collection<UUID>,
    ): List<NotificationRecipient>

    fun countByRecipientUserIdAndReadAtIsNull(userId: UUID): Long
}
