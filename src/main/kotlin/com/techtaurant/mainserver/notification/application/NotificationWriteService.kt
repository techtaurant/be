package com.techtaurant.mainserver.notification.application

import com.techtaurant.mainserver.comment.entity.Comment
import com.techtaurant.mainserver.comment.enums.CommentStatus
import com.techtaurant.mainserver.comment.infrastructure.out.CommentRepository
import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.notification.entity.Notification
import com.techtaurant.mainserver.notification.entity.NotificationArgument
import com.techtaurant.mainserver.notification.entity.NotificationRecipient
import com.techtaurant.mainserver.notification.enums.NotificationStatus
import com.techtaurant.mainserver.notification.enums.NotificationTargetType
import com.techtaurant.mainserver.notification.enums.NotificationType
import com.techtaurant.mainserver.notification.infrastructure.out.NotificationRepository
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.enums.PostStatus
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserStatus
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Locale
import java.util.UUID

@Service
class NotificationWriteService(
    private val notificationRepository: NotificationRepository,
    private val userRepository: UserRepository,
    private val postRepository: PostRepository,
    private val commentRepository: CommentRepository,
) {
    @Transactional
    fun createPostCommentNotification(
        actorUserId: UUID,
        recipientUserId: UUID,
        postId: UUID,
        commentId: UUID,
        locale: Locale? = null,
    ): UUID {
        val actor = resolveActor(actorUserId)
        val recipients = resolveRecipients(listOf(recipientUserId))
        resolvePost(postId)

        resolveComment(commentId)

        return createNotification(
            type = NotificationType.POST_COMMENT,
            recipients = recipients,
            argumentSpecs =
                listOf(
                    NotificationArgumentSpec(NotificationTargetType.USER, actor.id!!),
                    NotificationArgumentSpec(NotificationTargetType.POST, postId),
                    NotificationArgumentSpec(NotificationTargetType.COMMENT, commentId),
                ),
        )
    }

    @Transactional
    fun createCommentReplyNotification(
        actorUserId: UUID,
        recipientUserId: UUID,
        postId: UUID,
        commentId: UUID,
        locale: Locale? = null,
    ): UUID {
        val actor = resolveActor(actorUserId)
        val recipients = resolveRecipients(listOf(recipientUserId))
        resolvePost(postId)

        resolveComment(commentId)

        return createNotification(
            type = NotificationType.COMMENT_REPLY,
            recipients = recipients,
            argumentSpecs =
                listOf(
                    NotificationArgumentSpec(NotificationTargetType.USER, actor.id!!),
                    NotificationArgumentSpec(NotificationTargetType.POST, postId),
                    NotificationArgumentSpec(NotificationTargetType.COMMENT, commentId),
                ),
        )
    }

    @Transactional
    fun createFollowerPostNotification(
        actorUserId: UUID,
        recipientUserIds: List<UUID>,
        postId: UUID,
        locale: Locale? = null,
    ): UUID {
        val actor = resolveActor(actorUserId)
        val recipients = resolveRecipients(recipientUserIds)
        resolvePost(postId)

        return createNotification(
            type = NotificationType.FOLLOWER_POST,
            recipients = recipients,
            argumentSpecs =
                listOf(
                    NotificationArgumentSpec(NotificationTargetType.USER, actor.id!!),
                    NotificationArgumentSpec(NotificationTargetType.POST, postId),
                ),
        )
    }

    @Transactional
    fun createFollowNotification(
        actorUserId: UUID,
        recipientUserId: UUID,
        locale: Locale? = null,
    ): UUID {
        val actor = resolveActor(actorUserId)
        val recipients = resolveRecipients(listOf(recipientUserId))

        return createNotification(
            type = NotificationType.FOLLOW,
            recipients = recipients,
            argumentSpecs =
                listOf(
                    NotificationArgumentSpec(NotificationTargetType.USER, actor.id!!),
                ),
        )
    }

    @Transactional
    fun createPostLikeNotification(
        actorUserId: UUID,
        recipientUserId: UUID,
        postId: UUID,
        locale: Locale? = null,
    ): UUID {
        val actor = resolveActor(actorUserId)
        val recipients = resolveRecipients(listOf(recipientUserId))
        resolvePost(postId)

        return createNotification(
            type = NotificationType.POST_LIKE,
            recipients = recipients,
            argumentSpecs =
                listOf(
                    NotificationArgumentSpec(NotificationTargetType.USER, actor.id!!),
                    NotificationArgumentSpec(NotificationTargetType.POST, postId),
                ),
        )
    }

    @Transactional
    fun createCommentLikeNotification(
        actorUserId: UUID,
        recipientUserId: UUID,
        postId: UUID,
        commentId: UUID,
        locale: Locale? = null,
    ): UUID {
        val actor = resolveActor(actorUserId)
        val recipients = resolveRecipients(listOf(recipientUserId))
        resolvePost(postId)

        resolveComment(commentId)

        return createNotification(
            type = NotificationType.COMMENT_LIKE,
            recipients = recipients,
            argumentSpecs =
                listOf(
                    NotificationArgumentSpec(NotificationTargetType.USER, actor.id!!),
                    NotificationArgumentSpec(NotificationTargetType.POST, postId),
                    NotificationArgumentSpec(NotificationTargetType.COMMENT, commentId),
                ),
        )
    }

    @Transactional
    fun deletePostLikeNotification(
        actorUserId: UUID,
        postId: UUID,
    ) {
        deleteLikeNotification(NotificationType.POST_LIKE, actorUserId, NotificationTargetType.POST, postId)
    }

    @Transactional
    fun deleteCommentLikeNotification(
        actorUserId: UUID,
        commentId: UUID,
    ) {
        deleteLikeNotification(NotificationType.COMMENT_LIKE, actorUserId, NotificationTargetType.COMMENT, commentId)
    }

    private fun deleteLikeNotification(
        type: NotificationType,
        actorUserId: UUID,
        targetType: NotificationTargetType,
        targetId: UUID,
    ) {
        val notifications =
            notificationRepository.findAllByTypeAndActorAndTarget(type, actorUserId, targetType, targetId)
        if (notifications.isNotEmpty()) {
            notificationRepository.deleteAll(notifications)
        }
    }

    private fun createNotification(
        type: NotificationType,
        recipients: List<User>,
        argumentSpecs: List<NotificationArgumentSpec>,
    ): UUID {
        if (argumentSpecs.isEmpty()) {
            throw ApiException(NotificationStatus.NOTIFICATION_ARGUMENT_REQUIRED)
        }

        val notification =
            Notification(
                type = type,
            )

        argumentSpecs.distinct().forEach { spec ->
            notification.addArgument(
                NotificationArgument(
                    notification = notification,
                    targetType = spec.targetType,
                    targetId = spec.targetId,
                ),
            )
        }

        recipients.distinctBy { it.id }.forEach { recipient ->
            notification.addRecipient(
                NotificationRecipient(
                    notification = notification,
                    recipientUser = recipient,
                ),
            )
        }

        return notificationRepository.save(notification).id!!
    }

    private fun resolveActor(actorUserId: UUID): User {
        return userRepository.findById(actorUserId).orElseThrow {
            ApiException(UserStatus.ID_NOT_FOUND)
        }
    }

    private fun resolveRecipients(recipientUserIds: List<UUID>): List<User> {
        val distinctRecipientIds = recipientUserIds.distinct()
        if (distinctRecipientIds.isEmpty()) {
            throw ApiException(NotificationStatus.NOTIFICATION_RECIPIENT_REQUIRED)
        }

        val recipients = userRepository.findAllById(distinctRecipientIds)
        if (recipients.size != distinctRecipientIds.size) {
            throw ApiException(UserStatus.USER_NOT_FOUND)
        }

        val recipientsById = recipients.associateBy { it.id!! }
        return distinctRecipientIds.map { recipientId -> recipientsById.getValue(recipientId) }
    }

    private fun resolvePost(postId: UUID): Post {
        return postRepository.findById(postId).orElseThrow {
            ApiException(PostStatus.POST_NOT_FOUND)
        }
    }

    private fun resolveComment(commentId: UUID): Comment {
        return commentRepository.findById(commentId).orElseThrow {
            ApiException(CommentStatus.COMMENT_NOT_FOUND)
        }
    }

    private data class NotificationArgumentSpec(
        val targetType: NotificationTargetType,
        val targetId: UUID,
    )
}
