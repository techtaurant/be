package com.techtaurant.mainserver.notification.application

import com.techtaurant.mainserver.common.dto.CursorPageResponse
import com.techtaurant.mainserver.notification.dto.NotificationCursor
import com.techtaurant.mainserver.notification.dto.NotificationListItemResponse
import com.techtaurant.mainserver.notification.dto.NotificationUnreadCountResponse
import com.techtaurant.mainserver.notification.entity.NotificationRecipient
import com.techtaurant.mainserver.notification.enums.NotificationType
import com.techtaurant.mainserver.notification.infrastructure.out.NotificationArgumentRepository
import com.techtaurant.mainserver.notification.infrastructure.out.NotificationRecipientRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class NotificationReadService(
    private val notificationRecipientRepository: NotificationRecipientRepository,
    private val notificationArgumentRepository: NotificationArgumentRepository,
    notificationListItemRenderStrategies: List<NotificationListItemRenderStrategy>,
) {
    private val notificationListItemRenderStrategyByType =
        createNotificationListItemRenderStrategyByType(notificationListItemRenderStrategies)

    @Transactional(readOnly = true)
    fun getMyNotifications(
        userId: UUID,
        cursor: String?,
        size: Int,
    ): CursorPageResponse<NotificationListItemResponse> {
        val notificationCursor = cursor?.let { NotificationCursor.decode(it) }

        if (cursor != null && notificationCursor == null) {
            return CursorPageResponse(
                content = emptyList(),
                nextCursor = null,
                hasNext = false,
                size = 0,
            )
        }

        val recipients =
            if (notificationCursor == null) {
                notificationRecipientRepository.findAllByRecipientUserIdOrderByCreatedAtDescIdDesc(
                    userId = userId,
                    pageable = PageRequest.of(0, size + 1),
                )
            } else {
                notificationRecipientRepository.findPageByUserIdAndCursor(
                    userId = userId,
                    cursorCreatedAt = notificationCursor.createdAt,
                    cursorId = notificationCursor.id,
                    pageable = PageRequest.of(0, size + 1),
                )
            }

        val hasNext = recipients.size > size
        val content = recipients.take(size)
        val nextCursor =
            if (hasNext && content.isNotEmpty()) {
                NotificationCursor.from(content.last()).encode()
            } else {
                null
            }

        return CursorPageResponse(
            content = buildNotificationListItems(content),
            nextCursor = nextCursor,
            hasNext = hasNext,
            size = content.size,
        )
    }

    @Transactional(readOnly = true)
    fun getMyUnreadNotificationCount(userId: UUID): NotificationUnreadCountResponse {
        return NotificationUnreadCountResponse(
            unreadCount = notificationRecipientRepository.countByRecipientUserIdAndReadAtIsNull(userId),
        )
    }

    @Transactional
    fun markNotificationsRead(
        userId: UUID,
        notificationIds: List<UUID>,
    ): List<NotificationListItemResponse> {
        val distinctNotificationIds = notificationIds.distinct()
        if (distinctNotificationIds.isEmpty()) {
            return emptyList()
        }

        val unreadRecipients =
            notificationRecipientRepository.findAllByRecipientUserIdAndNotificationIdInAndReadAtIsNull(
                userId = userId,
                notificationIds = distinctNotificationIds,
            )

        if (unreadRecipients.isEmpty()) {
            return emptyList()
        }

        val unreadRecipientsByNotificationId = unreadRecipients.associateBy { it.notification.id!! }
        val updatedRecipients = distinctNotificationIds.mapNotNull(unreadRecipientsByNotificationId::get)
        val readAt = Instant.now()
        updatedRecipients.forEach { recipient ->
            recipient.markAsRead(readAt)
        }
        notificationRecipientRepository.saveAll(updatedRecipients)

        return buildNotificationListItems(updatedRecipients)
    }

    private fun buildNotificationListItems(recipients: List<NotificationRecipient>): List<NotificationListItemResponse> {
        if (recipients.isEmpty()) {
            return emptyList()
        }

        val notificationIds = recipients.map { it.notification.id!! }
        val argumentsByNotificationId =
            notificationArgumentRepository.findAllByNotificationIdInOrderByCreatedAtAsc(notificationIds)
                .groupBy { it.notification.id!! }
        val listItemsByNotificationId =
            recipients.groupBy { it.notification.type }
                .flatMap { (type, recipientsByType) ->
                    selectNotificationListItemRenderStrategy(type)
                        .render(
                            commands =
                                recipientsByType.map { recipient ->
                                    val notificationId = recipient.notification.id!!
                                    NotificationListItemRenderCommand(
                                        recipient = recipient,
                                        arguments = argumentsByNotificationId[notificationId].orEmpty(),
                                    )
                                },
                        ).entries
                }.associate { it.key to it.value }

        return recipients.mapNotNull { recipient ->
            listItemsByNotificationId[recipient.notification.id!!]
        }
    }

    private fun createNotificationListItemRenderStrategyByType(
        strategies: List<NotificationListItemRenderStrategy>,
    ): Map<NotificationType, NotificationListItemRenderStrategy> {
        val strategiesByType = strategies.groupBy { it.type }
        val duplicatedTypes = strategiesByType.filterValues { it.size > 1 }.keys
        require(duplicatedTypes.isEmpty()) {
            "알림 목록 아이템 렌더 전략이 중복 등록되었습니다: ${duplicatedTypes.joinToString()}"
        }

        val missingTypes = NotificationType.entries.filterNot { strategiesByType.containsKey(it) }
        require(missingTypes.isEmpty()) {
            "알림 목록 아이템 렌더 전략이 누락되었습니다: ${missingTypes.joinToString()}"
        }

        return strategiesByType.mapValues { (_, strategyGroup) -> strategyGroup.single() }
    }

    private fun selectNotificationListItemRenderStrategy(type: NotificationType): NotificationListItemRenderStrategy =
        notificationListItemRenderStrategyByType.getValue(type)
}
