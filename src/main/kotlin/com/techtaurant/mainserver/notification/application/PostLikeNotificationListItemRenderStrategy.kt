package com.techtaurant.mainserver.notification.application

import com.techtaurant.mainserver.notification.dto.NotificationListItemResponse
import com.techtaurant.mainserver.notification.enums.NotificationTargetType
import com.techtaurant.mainserver.notification.enums.NotificationType
import com.techtaurant.mainserver.user.entity.User
import org.springframework.stereotype.Component
import java.util.UUID

@Component
internal class PostLikeNotificationListItemRenderStrategy(
    private val notificationPayloadService: NotificationPayloadService,
    private val notificationPayloadResourceResolver: NotificationPayloadResourceResolver,
) : NotificationListItemRenderStrategy {
    override val type: NotificationType = NotificationType.POST_LIKE

    override fun render(commands: List<NotificationListItemRenderCommand>): Map<UUID, NotificationListItemResponse> {
        if (commands.isEmpty()) {
            return emptyMap()
        }

        val actorUserIds = commands.mapNotNull { it.arguments.findTargetId(NotificationTargetType.USER) }
        val actorsById = notificationPayloadResourceResolver.findUsersById(actorUserIds)
        val actorProfileImageUrlByUserId =
            notificationPayloadResourceResolver.resolveActorProfileImageUrlByUserId(actorsById)

        val postIds = commands.mapNotNull { it.arguments.findTargetId(NotificationTargetType.POST) }
        val postsById = notificationPayloadResourceResolver.findPostsById(postIds)

        return commands.associate { command ->
            val recipient = command.recipient
            val notificationId = recipient.notification.id!!
            val actor = command.arguments.findTargetId(NotificationTargetType.USER)?.let(actorsById::get)
            val post = command.arguments.findTargetId(NotificationTargetType.POST)?.let(postsById::get)
            val media = createMedia(actor, actorProfileImageUrlByUserId)

            notificationId to
                NotificationListItemResponse.from(
                    recipient = recipient,
                    payloadHtml =
                        notificationPayloadService.buildPayload(
                            messageKey = MESSAGE_KEY,
                            messageArguments = listOf(actor?.name.orEmpty(), post?.title.orEmpty()),
                        ),
                    thumbnailUrl = notificationPayloadService.resolveThumbnailUrl(media),
                    arguments = command.arguments,
                )
        }
    }

    private fun createMedia(
        actor: User?,
        actorProfileImageUrlByUserId: Map<UUID, String>,
    ): NotificationPayloadService.NotificationPayloadMedia =
        NotificationPayloadService.NotificationPayloadMedia(
            url = notificationPayloadResourceResolver.resolveActorProfileImageUrl(actor, actorProfileImageUrlByUserId),
        )

    private companion object {
        const val MESSAGE_KEY = "notification.payload.post-like"
    }
}
