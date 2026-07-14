package com.techtaurant.mainserver.notification.infrastructure.out

import com.techtaurant.mainserver.notification.entity.NotificationRecipient
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface NotificationRecipientRepository : JpaRepository<NotificationRecipient, UUID>, NotificationRecipientRepositoryCustom {
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
