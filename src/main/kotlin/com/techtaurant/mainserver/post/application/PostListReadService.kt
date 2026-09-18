package com.techtaurant.mainserver.post.application

import com.techtaurant.mainserver.common.dto.CursorPageResponse
import com.techtaurant.mainserver.post.dto.CategoryResponse
import com.techtaurant.mainserver.post.dto.DraftListItemResponse
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
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 게시물 목록 조회 서비스
 */
@Service
@Transactional(readOnly = true)
class PostListReadService(
    private val postRepository: PostRepository,
    private val postMetadataReadService: PostMetadataReadService,
    private val postViewerStateReadService: PostViewerStateReadService,
    private val userProfileImageResolver: UserProfileImageResolver,
    private val expiredDraftCleanupService: ExpiredDraftCleanupService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val POST_LIST_CONTENT_MAX_LENGTH = 2000

        // 목록 조회가 정리까지 떠안으므로, 밀린 물량이 많은 사용자의 응답이 그만큼 느려지지 않도록 상한을 둔다.
        // 이 상한을 넘긴 나머지는 정리 배치가 회수한다.
        private const val MAX_EXPIRED_DRAFT_DELETE_COUNT_PER_REQUEST = 100
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
     * @param keyword 제목 또는 본문 부분 일치 검색어 (null이면 미적용)
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

    private fun getPostPage(
        cursor: String?,
        size: Int,
        period: PostPeriod,
        sortType: PostSortType,
        currentUserId: UUID?,
        authorId: UUID?,
        categoryId: UUID?,
        tagIds: List<UUID>?,
        keyword: String?,
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
    // 만료 임시저장 정리가 자기 트랜잭션에서 돌기 때문에, 이 메서드가 트랜잭션을 열면
    // 요청 하나가 커넥션 두 개를 동시에 잡는다. 단일 조회 쿼리와 순수 매핑뿐이라 트랜잭션이 필요 없다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun getMyDrafts(
        userId: UUID,
        cursor: String?,
        size: Int,
    ): CursorPageResponse<DraftListItemResponse> {
        deleteExpiredDraftsBeforeListing(userId)

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

    /**
     * 목록을 내려주기 전에 그 사용자의 만료 임시저장을 정리합니다.
     * 정리 배치가 같은 대상을 다시 지우므로, 여기서 실패하면 목록 응답을 막는 대신 로그만 남기고 넘어갑니다.
     *
     * @param userId 사용자 ID
     */
    private fun deleteExpiredDraftsBeforeListing(userId: UUID) {
        try {
            expiredDraftCleanupService.deleteExpiredDrafts(MAX_EXPIRED_DRAFT_DELETE_COUNT_PER_REQUEST, userId)
        } catch (e: Exception) {
            log.error("Failed to delete expired drafts of user {}", userId, e)
        }
    }

    private fun encodeDraftCursor(
        updatedAt: Instant,
        id: UUID,
    ): String {
        return "${updatedAt}_$id"
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
