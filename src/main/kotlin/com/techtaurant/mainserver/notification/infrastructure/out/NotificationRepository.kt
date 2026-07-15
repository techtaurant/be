package com.techtaurant.mainserver.notification.infrastructure.out

import com.techtaurant.mainserver.notification.entity.Notification
import org.springframework.data.repository.Repository
import java.util.UUID

interface NotificationRepository : Repository<Notification, UUID>, NotificationRepositoryCustom {
    override fun save(notification: Notification): Notification

    override fun deleteAll(notifications: Iterable<Notification>)

    override fun findAll(): List<Notification>

    override fun findById(id: UUID): java.util.Optional<Notification>
}
