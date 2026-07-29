package com.techtaurant.mainserver.post.application

import com.techtaurant.mainserver.attachment.application.AttachmentService
import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.common.dto.CursorPageResponse
import com.techtaurant.mainserver.post.dto.CategoryResponse
import com.techtaurant.mainserver.post.dto.DraftListItemResponse
import com.techtaurant.mainserver.post.dto.PostContentListItemResponse
import com.techtaurant.mainserver.post.dto.PostCursor
import com.techtaurant.mainserver.post.dto.PostListItemResponse
import com.techtaurant.mainserver.post.dto.PostListTagResponse
import com.techtaurant.mainserver.post.dto.PostMetadataResponse
import com.techtaurant.mainserver.post.dto.PostViewerStateResponse
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.entity.PostPeriod
import com.techtaurant.mainserver.post.entity.PostSortType
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.user.application.UserProfileImageResolver
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 게시물 목록 조회 서비스
 */
@Service
@Transactional(readOnly = true)
class PostListReadService(
    private val postRepository: PostRepository,
    private val attachmentService: AttachmentService,
    private val postMetadataReadService: PostMetadataReadService,
    private val postViewerStateReadService: PostViewerStateReadService,
    private val userProfileImageResolver: UserProfileImageResolver,
) {
    companion object {
        private const val STALE_DRAFT_DAYS = 14
        private const val POST_LIST_CONTENT_MAX_LENGTH = 2000
    }

    /**
     * 게시물 목록을 커서 기반 페이지네이션으로 조회
     *
     * authorId 지정 시 해당 사용자의 게시물만 조회하며, 본인 조회 시 PUBLISHED/PRIVATE 포함, 타인 조회 시 PUBLISHED만 반환.
     * authorId 미지정 시 전체 게시물 조회하며, open-api 목록에서는 DRAFT를 제외합니다.
     *
     * @param cursor 이전 응답의 nextCursor (null이면 첫 페이지)
     * @param size 페이지 크기
     * @param period 기간 필터 (WEEK, MONTH, YEAR, ALL)
     * @param sortType 정렬 기준 (LATEST, VIEW, LIKE, COMMENT)
     * @param currentUserId 현재 로그인 사용자 ID (비회원이면 null)
     * @param authorId 작성자 필터 (null이면 전체 조회)
     * @param categoryId 카테고리 필터 (null이면 전체, authorId 지정 시에만 적용)
     * @param tagIds 태그 UUID 필터 (여러 개 전달 시 OR 조건)
     * @param keyword 제목 또는 본문 부분 일치 검색어 (null이면 미적용, 대소문자 무시)
     * @return 커서 기반 페이지 응답
     */
    fun getPosts(
        cursor: String?,
        size: Int,
        period: PostPeriod = PostPeriod.ALL,
        sortType: PostSortType = PostSortType.LATEST,
        currentUserId: UUID? = null,
        authorId: UUID? = null,
        categoryId: UUID? = null,
        tagIds: List<UUID>? = null,
        keyword: String? = null,
    ): CursorPageResponse<PostListItemResponse> {
        val postPage =
            getPostPage(
                cursor = cursor,
                size = size,
                period = period,
                sortType = sortType,
                currentUserId = currentUserId,
                authorId = authorId,
                categoryId = categoryId,
                tagIds = tagIds,
                keyword = keyword,
            )
        val content = postPage.content

        val metadataByPostId =
            postMetadataReadService
                .getPostMetadataForPosts(content)
                .associateBy { it.postId }
        val viewerStateByPostId =
            currentUserId
                ?.let { postViewerStateReadService.getPostViewerStatesForPosts(it, content) }
                ?.associateBy { it.postId }
                .orEmpty()
        val authorProfileImageUrlByUserId =
            userProfileImageResolver.resolve(content.map { it.author }.distinctBy { it.id })

        return CursorPageResponse(
            content =
                content.map { post ->
                    val postId = post.id!!
                    convertToResponse(
                        post = post,
                        metadata = metadataByPostId.getValue(postId),
                        viewerState = viewerStateByPostId[postId],
                        authorProfileImageUrl =
                            authorProfileImageUrlByUserId[post.author.id] ?: post.author.getFallbackProfileImageUrl(),
                    )
                },
            nextCursor = postPage.nextCursor,
            hasNext = postPage.hasNext,
            size = postPage.size,
        )
    }

    /**
     * 게시물 정적 콘텐츠 목록을 커서 기반 페이지네이션으로 조회합니다.
     *
     * 동적 집계, 사용자 상태, presigned URL 생성 없이 SSG/ISR에 적합한 콘텐츠 필드만 반환합니다.
     */
    fun getPostContents(
        cursor: String?,
        size: Int,
        period: PostPeriod = PostPeriod.ALL,
        sortType: PostSortType = PostSortType.LATEST,
        authorId: UUID? = null,
        categoryId: UUID? = null,
        tagIds: List<UUID>? = null,
    ): CursorPageResponse<PostContentListItemResponse> {
        val postPage =
            getPostPage(
                cursor = cursor,
                size = size,
                period = period,
                sortType = sortType,
                currentUserId = null,
                authorId = authorId,
                categoryId = categoryId,
                tagIds = tagIds,
            )

        return CursorPageResponse(
            content = postPage.content.map(PostContentListItemResponse::from),
            nextCursor = postPage.nextCursor,
            hasNext = postPage.hasNext,
            size = postPage.size,
        )
    }

    private fun getPostPage(
        cursor: String?,
        size: Int,
        period: PostPeriod,
        sortType: PostSortType,
        currentUserId: UUID?,
        authorId: UUID?,
        categoryId: UUID?,
        tagIds: List<UUID>?,
        keyword: String? = null,
    ): CursorPageResponse<Post> {
        val postCursor = cursor?.let { PostCursor.decode(it) }
        val normalizedTagIds = normalizeTagIds(tagIds)

        if (cursor != null && postCursor == null) {
            return emptyPostPage()
        }

        val visibilityScope = PostVisibilityScope.from(currentUserId = currentUserId, authorId = authorId)
        val sortedPosts =
            postRepository.findPostsWithConditions(
                cursor = postCursor,
                size = size + 1,
                period = period,
                sortType = sortType,
                authorId = visibilityScope.authorId,
                statuses = visibilityScope.statuses,
                categoryId = visibilityScope.categoryIdOrNull(categoryId),
                visibleToUserId = visibilityScope.visibleToUserId,
                tagIds = normalizedTagIds,
                viewerId = visibilityScope.viewerId,
                keyword = keyword,
            )
        val hasNext = sortedPosts.size > size
        val contentWithSortValues = sortedPosts.take(size)
        val content = contentWithSortValues.map { it.post }

        val nextCursor =
            if (hasNext && contentWithSortValues.isNotEmpty()) {
                createPostCursor(contentWithSortValues.last(), sortType).encode()
            } else {
                null
            }

        return CursorPageResponse(
            content = content,
            nextCursor = nextCursor,
            hasNext = hasNext,
            size = content.size,
        )
    }

    private fun createPostCursor(
        sortedPost: PostWithSortValue,
        sortType: PostSortType,
    ): PostCursor {
        return PostCursor.from(
            post = sortedPost.post,
            sortType = sortType,
            sortValue = sortedPost.sortValue,
        )
    }

    private fun emptyPostPage(): CursorPageResponse<Post> =
        CursorPageResponse(
            content = emptyList(),
            nextCursor = null,
            hasNext = false,
            size = 0,
        )

    private fun normalizeTagIds(tagIds: List<UUID>?): List<UUID>? {
        val normalizedTagIds = tagIds?.distinct()

        return normalizedTagIds?.takeIf { it.isNotEmpty() }
    }

    /**
     * 현재 사용자의 DRAFT 게시물 목록을 커서 기반으로 조회합니다.
     * 최근 수정일 기준 내림차순으로 정렬됩니다.
     *
     * @param userId 사용자 ID
     * @param cursor 커서 문자열 (형식: "updatedAt_id", 없으면 첫 페이지)
     * @param size 페이지 크기
     * @return DRAFT 게시물 목록 커서 페이지
     */
    @Transactional
    fun getMyDrafts(
        userId: UUID,
        cursor: String?,
        size: Int,
    ): CursorPageResponse<DraftListItemResponse> {
        deleteExpiredDrafts(userId)

        val posts =
            if (cursor == null) {
                postRepository.findDraftsByAuthorFirstPage(userId, size + 1)
            } else {
                val (cursorUpdatedAt, cursorId) = parseDraftCursor(cursor)
                postRepository.findDraftsByAuthorWithCursor(userId, cursorUpdatedAt, cursorId, size + 1)
            }

        val hasNext = posts.size > size
        val content = posts.take(size).map { DraftListItemResponse.from(it) }
        val nextCursor =
            if (hasNext) {
                val lastPost = posts[size - 1]
                encodeDraftCursor(lastPost.updatedAt, lastPost.id!!)
            } else {
                null
            }

        return CursorPageResponse(
            content = content,
            nextCursor = nextCursor,
            hasNext = hasNext,
            size = content.size,
        )
    }

    private fun parseDraftCursor(cursor: String): Pair<Instant, UUID> {
        val parts = cursor.split("_")
        val id = UUID.fromString(parts[1])
        return Pair(parseCursorInstant(parts[0]), id)
    }

    private fun parseCursorInstant(value: String): Instant = value.toLongOrNull()?.let(Instant::ofEpochMilli) ?: Instant.parse(value)

    private fun encodeDraftCursor(
        updatedAt: Instant,
        id: UUID,
    ): String {
        return "${updatedAt}_$id"
    }

    /**
     * 2주 이상 경과한 DRAFT 게시물을 삭제합니다.
     * S3 첨부파일도 함께 삭제됩니다.
     *
     * @param userId 사용자 ID
     */
    private fun deleteExpiredDrafts(userId: UUID) {
        val expirationDate = Instant.now().minus(STALE_DRAFT_DAYS.toLong(), ChronoUnit.DAYS)

        val staleDrafts = postRepository.findStaleDraftsByAuthor(userId, expirationDate)
        staleDrafts.forEach { post ->
            attachmentService.deleteAttachmentsByReference(post.id!!, AttachmentReferenceType.POST)
            postRepository.delete(post)
        }
    }

    /**
     * Post 엔티티를 PostListItemResponse DTO로 변환합니다.
     * 썸네일 URL과 읽음 여부를 계산하여 포함합니다.
     *
     * @param post 게시물 엔티티
     * @return 응답 DTO
     */
    private fun convertToResponse(
        post: Post,
        metadata: PostMetadataResponse,
        viewerState: PostViewerStateResponse?,
        authorProfileImageUrl: String,
    ): PostListItemResponse {
        return PostListItemResponse(
            id = post.id!!,
            title = post.title,
            content = post.content.take(POST_LIST_CONTENT_MAX_LENGTH),
            authorId = post.author.id!!,
            authorName = post.author.name,
            authorProfileImageUrl = authorProfileImageUrl,
            thumbnailUrl = metadata.thumbnailUrl,
            category = post.category?.let(CategoryResponse::from),
            isRead = viewerState?.isRead ?: false,
            tags = post.tags.map { PostListTagResponse.from(it) },
            viewCount = metadata.viewCount,
            likeCount = metadata.likeCount,
            commentCount = metadata.commentCount,
            status = metadata.status,
            createdAt = post.createdAt,
            updatedAt = post.updatedAt,
        )
    }
}
