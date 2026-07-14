package com.techtaurant.mainserver.notification.infrastructure.out

import com.techtaurant.mainserver.notification.entity.Notification
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface NotificationRepository : JpaRepository<Notification, UUID>, NotificationRepositoryCustom
