package com.techtaurant.mainserver.notification.application

import com.techtaurant.mainserver.attachment.application.AttachmentService
import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.post.application.PostThumbnailResolver
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.user.application.UserProfileImageResolver
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class NotificationPayloadResourceResolver(
    private val userRepository: UserRepository,
    private val postRepository: PostRepository,
    private val attachmentService: AttachmentService,
    private val userProfileImageResolver: UserProfileImageResolver,
    private val postThumbnailResolver: PostThumbnailResolver,
    @param:Value("\${app.default-post-thumbnail-url}")
    private val defaultPostThumbnailUrl: String,
    @param:Value("\${app.default-user-thumbnail-url:/static/images/user-thumbnail.png}")
    private val defaultUserThumbnailUrl: String,
    @param:Value("\${swagger.base-url}")
    private val baseUrl: String,
) {
    fun findUsersById(userIds: Collection<UUID>): Map<UUID, User> {
        if (userIds.isEmpty()) {
            return emptyMap()
        }

        return userRepository.findAllById(userIds.distinct()).associateBy { it.id!! }
    }

    fun findPostsById(postIds: Collection<UUID>): Map<UUID, Post> {
        if (postIds.isEmpty()) {
            return emptyMap()
        }

        return postRepository.findAllById(postIds.distinct()).associateBy { it.id!! }
    }

    fun resolveActorProfileImageUrlByUserId(usersById: Map<UUID, User>): Map<UUID, String> {
        if (usersById.isEmpty()) {
            return emptyMap()
        }

        return userProfileImageResolver.resolve(usersById.values.toList())
    }

    fun resolveActorProfileImageUrl(
        actor: User?,
        actorProfileImageUrlByUserId: Map<UUID, String>,
    ): String {
        val resolvedImageUrl = actor?.id?.let(actorProfileImageUrlByUserId::get)
        return absoluteUrl(resolvedImageUrl).ifBlank { defaultUserThumbnailUrl() }
    }

    fun resolvePostThumbnailUrlByPostId(postsById: Map<UUID, Post>): Map<UUID, String> {
        if (postsById.isEmpty()) {
            return emptyMap()
        }

        val postIds = postsById.keys.toList()
        val attachmentsByPostId =
            attachmentService.getConfirmedAttachmentsByReferenceIds(postIds, AttachmentReferenceType.POST)
        val thumbnailAttachmentByPostId =
            postsById.values.associate { post ->
                val postId = post.id!!
                val attachments = attachmentsByPostId[postId].orEmpty()

                postId to postThumbnailResolver.resolve(post, attachments)
            }
        val presignedThumbnailUrlByAttachmentId =
            thumbnailAttachmentByPostId.values
                .filterNotNull()
                .takeIf { it.isNotEmpty() }
                ?.let { attachmentService.generatePresignedDownloadUrlMapByAttachments(it) }
                ?: emptyMap()

        return postsById.values.associate { post ->
            val postId = post.id!!
            val thumbnailUrl =
                thumbnailAttachmentByPostId[postId]
                    ?.id
                    ?.let { attachmentId -> presignedThumbnailUrlByAttachmentId[attachmentId] }
                    ?: defaultPostThumbnailUrl()
            postId to thumbnailUrl
        }
    }

    fun defaultPostThumbnailUrl(): String = absoluteUrl(defaultPostThumbnailUrl)

    fun defaultUserThumbnailUrl(): String = absoluteUrl(defaultUserThumbnailUrl)

    fun absoluteUrl(url: String?): String {
        val candidate = url?.trim().orEmpty()
        if (candidate.isBlank()) {
            return candidate
        }

        return when {
            candidate.startsWith("http://") -> candidate
            candidate.startsWith("https://") -> candidate
            candidate.startsWith("/") -> "${baseUrl.trimEnd('/')}$candidate"
            else -> candidate
        }
    }
}
