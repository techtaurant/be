package com.techtaurant.mainserver.notification.infrastructure.out

import com.techtaurant.mainserver.notification.entity.NotificationRecipient
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.Repository
import java.time.Instant
import java.util.UUID

interface NotificationRecipientRepository : Repository<NotificationRecipient, UUID>, NotificationRecipientRepositoryCustom {
    override fun save(recipient: NotificationRecipient): NotificationRecipient

    override fun saveAll(recipients: Iterable<NotificationRecipient>): List<NotificationRecipient>

    override fun findById(id: UUID): java.util.Optional<NotificationRecipient>

    override fun findAllByNotificationIdOrderByCreatedAtAsc(notificationId: UUID): List<NotificationRecipient>

    override fun findAllByRecipientUserIdOrderByCreatedAtDescIdDesc(
        userId: UUID,
        pageable: Pageable,
    ): List<NotificationRecipient>

    override fun findPageByUserIdAndCursor(
        userId: UUID,
        cursorCreatedAt: Instant,
        cursorId: UUID,
        pageable: Pageable,
    ): List<NotificationRecipient>

    override fun findAllByRecipientUserIdAndNotificationIdInAndReadAtIsNull(
        userId: UUID,
        notificationIds: Collection<UUID>,
    ): List<NotificationRecipient>

    override fun countByRecipientUserIdAndReadAtIsNull(userId: UUID): Long
}
