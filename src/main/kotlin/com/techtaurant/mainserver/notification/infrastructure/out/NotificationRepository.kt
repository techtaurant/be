package com.techtaurant.mainserver.notification.infrastructure.out

import com.techtaurant.mainserver.notification.entity.Notification
import com.techtaurant.mainserver.notification.enums.NotificationTargetType
import com.techtaurant.mainserver.notification.enums.NotificationType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface NotificationRepository : JpaRepository<Notification, UUID> {
    @Query(
        """
        select distinct n
        from Notification n
        join n.arguments actor
        join n.arguments target
        where n.type = :type
          and actor.targetType = :actorTargetType
          and actor.targetId = :actorUserId
          and target.targetType = :targetType
          and target.targetId = :targetId
        """,
    )
    fun findAllByTypeAndActorAndTarget(
        @Param("type") type: NotificationType,
        @Param("actorUserId") actorUserId: UUID,
        @Param("targetType") targetType: NotificationTargetType,
        @Param("targetId") targetId: UUID,
        @Param("actorTargetType") actorTargetType: NotificationTargetType = NotificationTargetType.USER,
    ): List<Notification>
}
