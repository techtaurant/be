package com.techtaurant.mainserver.notification.infrastructure.out

import com.techtaurant.mainserver.notification.entity.NotificationArgument
import org.springframework.data.repository.Repository
import java.util.UUID

interface NotificationArgumentRepository : Repository<NotificationArgument, UUID>, NotificationArgumentRepositoryCustom {
    override fun findAllByNotificationIdOrderByCreatedAtAsc(notificationId: UUID): List<NotificationArgument>

    override fun findAllByNotificationIdInOrderByCreatedAtAsc(notificationIds: Collection<UUID>): List<NotificationArgument>
}
