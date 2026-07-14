package com.techtaurant.mainserver.notification.infrastructure.out

import com.techtaurant.mainserver.notification.entity.NotificationArgument
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface NotificationArgumentRepository : JpaRepository<NotificationArgument, UUID>, NotificationArgumentRepositoryCustom {
    override fun findAllByNotificationIdOrderByCreatedAtAsc(notificationId: UUID): List<NotificationArgument>

    override fun findAllByNotificationIdInOrderByCreatedAtAsc(notificationIds: Collection<UUID>): List<NotificationArgument>
}
