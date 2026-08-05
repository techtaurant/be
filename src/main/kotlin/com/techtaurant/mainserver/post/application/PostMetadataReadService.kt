package com.techtaurant.mainserver.post.application

import com.techtaurant.mainserver.attachment.application.AttachmentService
import com.techtaurant.mainserver.attachment.entity.Attachment
import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.post.dto.PostDetailAttachmentPresignedUrlResponse
import com.techtaurant.mainserver.post.dto.PostMetadataResponse
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class PostMetadataReadService(
    private val postRepository: PostRepository,
    private val attachmentService: AttachmentService,
    private val postThumbnailResolver: PostThumbnailResolver,
    @param:Value("\${app.default-post-thumbnail-url}")
    private val defaultThumbnailUrl: String,
    @param:Value("\${swagger.base-url}")
    private val baseUrl: String,
) {
    fun getPostMetadata(postIds: List<UUID>): List<PostMetadataResponse> {
        val posts = getPublishedPostsByIds(postIds)

        return getPostMetadataForPosts(posts)
    }

    fun getPostMetadataForPosts(posts: List<Post>): List<PostMetadataResponse> {
        val loadedPostIds = posts.mapNotNull { it.id }
        if (loadedPostIds.isEmpty()) {
            return emptyList()
        }

        val attachmentsByPostId =
            attachmentService.getConfirmedAttachmentsByReferenceIds(
                referenceIds = loadedPostIds,
                referenceType = AttachmentReferenceType.POST,
            )
        val presignedUrlByAttachmentId = generatePresignedUrlByAttachmentId(attachmentsByPostId)

        return posts.map { post ->
            val postId = post.id!!
            val attachments = attachmentsByPostId[postId].orEmpty()
            val thumbnailUrl = resolveThumbnailUrl(post, attachments, presignedUrlByAttachmentId)

            PostMetadataResponse(
                postId = postId,
                viewCount = post.viewCount,
                likeCount = post.likeCount,
                commentCount = post.commentCount,
                status = post.status,
                thumbnailUrl = thumbnailUrl,
                attachmentPresignedUrls = buildAttachmentPresignedUrls(attachments, presignedUrlByAttachmentId),
            )
        }
    }

    private fun getPublishedPostsByIds(postIds: List<UUID>): List<Post> {
        val normalizedPostIds = postIds.distinct()
        if (normalizedPostIds.isEmpty()) {
            return emptyList()
        }

        val postById = postRepository.findPublishedPostsByIdIn(normalizedPostIds).associateBy { it.id!! }

        return normalizedPostIds.mapNotNull { postById[it] }
    }

    private fun generatePresignedUrlByAttachmentId(attachmentsByPostId: Map<UUID, List<Attachment>>): Map<UUID, String> {
        val attachments = attachmentsByPostId.values.flatten()
        if (attachments.isEmpty()) {
            return emptyMap()
        }

        return attachmentService.generatePresignedDownloadUrlMapByAttachments(attachments)
    }

    private fun resolveThumbnailUrl(
        post: Post,
        attachments: List<Attachment>,
        presignedUrlByAttachmentId: Map<UUID, String>,
    ): String {
        val thumbnailAttachment = postThumbnailResolver.resolve(post, attachments)

        return thumbnailAttachment?.id?.let { presignedUrlByAttachmentId[it] } ?: "$baseUrl$defaultThumbnailUrl"
    }

    private fun buildAttachmentPresignedUrls(
        attachments: List<Attachment>,
        presignedUrlByAttachmentId: Map<UUID, String>,
    ): List<PostDetailAttachmentPresignedUrlResponse> {
        return attachments.mapNotNull { attachment ->
            val attachmentId = attachment.id ?: return@mapNotNull null
            presignedUrlByAttachmentId[attachmentId]?.let { presignedUrl ->
                PostDetailAttachmentPresignedUrlResponse.from(attachmentId, presignedUrl)
            }
        }
    }
}
