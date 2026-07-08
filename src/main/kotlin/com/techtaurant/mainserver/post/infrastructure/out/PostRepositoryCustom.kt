package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.post.application.PostWithSortValue
import com.techtaurant.mainserver.post.dto.PostCursor
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.entity.PostPeriod
import com.techtaurant.mainserver.post.entity.PostSortType
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import java.util.UUID

/**
 * 게시물 동적 쿼리를 위한 커스텀 Repository
 */
interface PostRepositoryCustom {
    /**
     * 동적 조건으로 게시물 목록 조회
     *
     * @param cursor 커서 (null이면 첫 페이지)
     * @param size 페이지 크기
     * @param period 기간 필터
     * @param sortType 정렬 타입
     * @param authorId 작성자 ID 필터 (null이면 미적용)
     * @param statuses 게시물 상태 필터 (null이면 PUBLISHED만 조회)
     * @param categoryId 카테고리 ID 필터 (null이면 미적용)
     * @param visibleToUserId PUBLISHED + 해당 사용자의 PRIVATE 게시물 조회 (null이면 미적용, statuses보다 우선)
     * @param tagIds 태그 UUID 필터 (여러 개 전달 시 OR 조건)
     * @return 실제 정렬값을 포함한 게시물 목록
     */
    fun findPostsWithConditions(
        cursor: PostCursor?,
        size: Int,
        period: PostPeriod,
        sortType: PostSortType,
        authorId: UUID? = null,
        statuses: List<PostStatusEnum>? = null,
        categoryId: UUID? = null,
        visibleToUserId: UUID? = null,
        tagIds: List<UUID>? = null,
        viewerId: UUID? = null,
    ): List<PostWithSortValue>

    fun findPostDetailByIdForViewer(
        postId: UUID,
        viewerId: UUID?,
    ): Post?
}
