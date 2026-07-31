package com.techtaurant.mainserver.post.application

import com.techtaurant.mainserver.attachment.application.AttachmentService
import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.common.lock.DistributedLock
import com.techtaurant.mainserver.notification.application.NotificationWriteService
import com.techtaurant.mainserver.post.dto.CreatePostRequest
import com.techtaurant.mainserver.post.dto.PostResponse
import com.techtaurant.mainserver.post.dto.UpdatePostRequest
import com.techtaurant.mainserver.post.entity.Category
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.entity.Tag
import com.techtaurant.mainserver.post.enums.PostStatus
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.post.infrastructure.out.CategoryRepository
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserStatus
import com.techtaurant.mainserver.user.infrastructure.out.UserFollowRepository
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class PostWriteService(
    private val postRepository: PostRepository,
    private val categoryRepository: CategoryRepository,
    private val tagWriteService: TagWriteService,
    private val userRepository: UserRepository,
    private val userFollowRepository: UserFollowRepository,
    private val distributedLock: DistributedLock,
    private val attachmentService: AttachmentService,
    private val notificationWriteService: NotificationWriteService,
) {
    companion object {
        private const val MAX_CATEGORY_DEPTH = 5
    }

    /**
     * 게시물을 생성합니다.
     * 카테고리/태그 생성 시 락과 트랜잭션이 함께 관리되며, 게시물 저장은 별도 트랜잭션에서 수행됩니다.
     * DRAFT 상태일 경우 빈 제목/본문에 기본값을 설정하고, PUBLISHED/PRIVATE 상태일 경우 제목/본문이 필수입니다.
     *
     * @param userId 작성자 ID
     * @param request 게시물 생성 요청
     * @return 생성된 게시물 응답
     * @throws ApiException 카테고리 depth 초과 시 CATEGORY_DEPTH_EXCEEDED, 필수 필드 누락 시 TITLE_REQUIRED/CONTENT_REQUIRED
     */
    @Transactional
    fun createPost(
        userId: UUID,
        request: CreatePostRequest,
    ): PostResponse {
        val author = findUserById(userId)
        val status = request.status ?: PostStatusEnum.PUBLISHED

        val title =
            when {
                request.title.isNullOrBlank() ->
                    if (status == PostStatusEnum.DRAFT) {
                        "새 게시물"
                    } else {
                        throw ApiException(PostStatus.TITLE_REQUIRED)
                    }
                else -> request.title
            }

        val content =
            when {
                request.content.isNullOrBlank() ->
                    if (status == PostStatusEnum.DRAFT) {
                        "Empty"
                    } else {
                        throw ApiException(PostStatus.CONTENT_REQUIRED)
                    }
                else -> request.content
            }

        val category = resolveCategory(request.categoryPath, author)
        val tags = resolvePostTags(request.tags)

        val post =
            Post(
                title = title,
                content = content,
                author = author,
                category = category,
                status = status,
            ).apply { replaceTags(tags) }

        request.createdAt?.let { post.createdAt = it }

        val isDraft = status == PostStatusEnum.DRAFT
        val attachmentIds =
            if (isDraft) {
                emptyList()
            } else {
                mergeAttachmentIds(
                    filterAttachmentIdsIncludedInContent(post.content, request.attachmentIds),
                    request.thumbnailAttachmentId,
                )
            }
        if (!isDraft) {
            post.thumbnailImage = request.thumbnailAttachmentId ?: attachmentIds.firstOrNull()
        }

        val savedPost = postRepository.save(post)

        if (!isDraft) {
            attachmentService.confirmAttachmentsByIds(
                referenceId = savedPost.id!!,
                referenceType = AttachmentReferenceType.POST,
                attachmentIds = attachmentIds,
            )
        }

        if (status == PostStatusEnum.PUBLISHED) {
            createFollowerPostNotification(savedPost)
        }

        return PostResponse.from(savedPost)
    }

    /**
     * 게시물을 수정합니다.
     * 요청에 포함된 필드만 업데이트하며, 작성자 권한을 검증합니다.
     * 상태 전환 시 DRAFT를 제외한 상태는 제목과 본문이 필수입니다.
     *
     * @param postId 게시물 ID
     * @param request 게시물 수정 요청
     * @param userId 요청 사용자 ID
     * @return 수정된 게시물 응답
     * @throws ApiException 게시물 없음(POST_NOT_FOUND), 권한 없음(CANNOT_MODIFY_OTHERS_POST), 필수 필드 누락(TITLE_REQUIRED/CONTENT_REQUIRED)
     */
    @Transactional
    fun updatePost(
        postId: UUID,
        request: UpdatePostRequest,
        userId: UUID,
    ): PostResponse {
        val post =
            postRepository.findPostByIdWithAuthor(postId)
                ?: throw ApiException(PostStatus.POST_NOT_FOUND)

        if (post.author.id != userId) {
            throw ApiException(PostStatus.CANNOT_MODIFY_OTHERS_POST)
        }

        request.title?.let { post.title = it }
        request.content?.let { post.content = it }
        request.categoryPath?.let { post.category = resolveCategory(it, post.author) }
        request.tags?.let { post.replaceTags(resolvePostTags(it)) }

        request.status?.let { newStatus ->
            if (newStatus != PostStatusEnum.DRAFT) {
                if (post.title.isBlank()) throw ApiException(PostStatus.TITLE_REQUIRED)
                if (post.content.isBlank()) throw ApiException(PostStatus.CONTENT_REQUIRED)
            }
            post.status = newStatus
        }

        val newStatus = request.status ?: post.status
        if (newStatus != PostStatusEnum.DRAFT) {
            val attachmentIdsIncludedInContent =
                mergeAttachmentIds(
                    filterAttachmentIdsIncludedInContent(post.content, request.attachmentIds),
                    request.thumbnailAttachmentId,
                )
            val thumbnailAttachmentId =
                resolveThumbnailAttachmentId(
                    requestThumbnailAttachmentId = request.thumbnailAttachmentId,
                    currentThumbnailAttachmentId = post.thumbnailImage,
                    attachmentIdsIncludedInContent = attachmentIdsIncludedInContent,
                )
            val keepAttachmentIds = mergeAttachmentIds(attachmentIdsIncludedInContent, thumbnailAttachmentId)

            attachmentService.confirmAttachmentsByIds(
                referenceId = postId,
                referenceType = AttachmentReferenceType.POST,
                attachmentIds = keepAttachmentIds,
            )

            attachmentService.deleteOrphanedAttachmentsByIds(
                referenceId = postId,
                referenceType = AttachmentReferenceType.POST,
                keepAttachmentIds = keepAttachmentIds,
            )

            post.thumbnailImage = thumbnailAttachmentId
        } else {
            post.thumbnailImage = null
        }

        val savedPost = postRepository.save(post)
        return PostResponse.from(savedPost)
    }

    /**
     * 게시물을 삭제합니다.
     * 연관된 S3 첨부파일과 DB 레코드를 함께 삭제합니다.
     *
     * @param postId 게시물 ID
     * @param userId 요청 사용자 ID
     * @throws ApiException 게시물 없음(POST_NOT_FOUND), 권한 없음(CANNOT_MODIFY_OTHERS_POST)
     */
    @Transactional
    fun deletePost(
        postId: UUID,
        userId: UUID,
    ) {
        val post =
            postRepository.findPostByIdWithAuthor(postId)
                ?: throw ApiException(PostStatus.POST_NOT_FOUND)

        if (post.author.id != userId) {
            throw ApiException(PostStatus.CANNOT_MODIFY_OTHERS_POST)
        }

        post.thumbnailImage = null
        attachmentService.deleteAttachmentsByReference(postId, AttachmentReferenceType.POST)
        postRepository.delete(post)
    }

    private fun findUserById(userId: UUID): User {
        return userRepository.findById(userId).orElseThrow {
            ApiException(UserStatus.ID_NOT_FOUND)
        }
    }

    private fun createFollowerPostNotification(post: Post) {
        val authorId = post.author.id ?: return
        val followerIds = userFollowRepository.findFollowerIdsByFollowingId(authorId)
        if (followerIds.isEmpty()) {
            return
        }

        notificationWriteService.createFollowerPostNotification(
            actorUserId = authorId,
            recipientUserIds = followerIds,
            postId = post.id!!,
        )
    }

    private fun filterAttachmentIdsIncludedInContent(
        content: String,
        requestedAttachmentIds: List<UUID>?,
    ): List<UUID> =
        requestedAttachmentIds
            .orEmpty()
            .distinct()
            .filter { attachmentId ->
                content.contains(attachmentId.toString())
            }

    private fun mergeAttachmentIds(
        attachmentIds: List<UUID>,
        thumbnailAttachmentId: UUID?,
    ): List<UUID> = (attachmentIds + listOfNotNull(thumbnailAttachmentId)).distinct()

    /**
     * 게시물 수정 후 최종 썸네일 attachmentId를 결정합니다.
     * 요청 썸네일이 있으면 그 값을 우선 사용하고, 없으면 본문에 여전히 포함된 기존 썸네일을 유지합니다.
     * 둘 다 없으면 본문에 남아 있는 첫 번째 attachment를 썸네일로 사용합니다.
     *
     * @param requestThumbnailAttachmentId 수정 요청에서 명시적으로 전달한 썸네일 attachmentId
     * @param currentThumbnailAttachmentId 게시물에 현재 저장된 썸네일 attachmentId
     * @param attachmentIdsIncludedInContent 수정 후 본문에 포함된 attachmentId 목록
     * @return 최종적으로 게시물에 저장할 썸네일 attachmentId, 없으면 null
     */
    private fun resolveThumbnailAttachmentId(
        requestThumbnailAttachmentId: UUID?,
        currentThumbnailAttachmentId: UUID?,
        attachmentIdsIncludedInContent: List<UUID>,
    ): UUID? {
        if (requestThumbnailAttachmentId != null) {
            return requestThumbnailAttachmentId
        }

        if (currentThumbnailAttachmentId in attachmentIdsIncludedInContent) {
            return currentThumbnailAttachmentId
        }

        return attachmentIdsIncludedInContent.firstOrNull()
    }

    /**
     * 카테고리 경로를 파싱하여 해당 카테고리를 반환합니다.
     * 존재하지 않는 카테고리는 자동으로 생성되며, 동시성 제어를 위해 락을 사용합니다.
     *
     * @param categoryPath 카테고리 경로 (예: "java/spring")
     * @param user 카테고리 소유자
     * @return 카테고리 엔티티 (경로가 없으면 null)
     */
    private fun resolveCategory(
        categoryPath: String?,
        user: User,
    ): Category? {
        if (categoryPath.isNullOrBlank()) {
            return null
        }

        val pathSegments = categoryPath.split("/").filter { it.isNotBlank() }

        if (pathSegments.isEmpty()) {
            return null
        }

        if (pathSegments.size > MAX_CATEGORY_DEPTH) {
            throw ApiException(PostStatus.CATEGORY_DEPTH_EXCEEDED)
        }

        var totalPath = ""
        var parentCategory: Category? = null

        for ((index, curPathSegment) in pathSegments.withIndex()) {
            totalPath = if (totalPath.isEmpty()) curPathSegment else "$totalPath/$curPathSegment"

            val lockKey = "category:${user.id}:$totalPath"
            parentCategory =
                distributedLock.withLockAndTransaction(lockKey) {
                    categoryRepository.findByUserAndPath(user, totalPath)
                        ?: categoryRepository.save(
                            Category(
                                user = user,
                                name = curPathSegment,
                                path = totalPath,
                                depth = index + 1,
                                parent = parentCategory,
                            ),
                        )
                }
        }

        return parentCategory
    }

    private fun resolvePostTags(tagNames: List<String>?): Set<Tag> = tagWriteService.resolveTags(tagNames.orEmpty().map(String::lowercase))
}
