package com.techtaurant.mainserver.post.infrastructure.`in`

import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.dto.CursorPageResponse
import com.techtaurant.mainserver.common.status.DefaultStatus
import com.techtaurant.mainserver.common.swagger.ApiCommonBadRequestAndUnknown
import com.techtaurant.mainserver.common.swagger.ApiErrorCodeResponse
import com.techtaurant.mainserver.common.swagger.ApiErrorCodeResponses
import com.techtaurant.mainserver.post.dto.PostDetailResponse
import com.techtaurant.mainserver.post.dto.PostListItemResponse
import com.techtaurant.mainserver.post.entity.PostPeriod
import com.techtaurant.mainserver.post.entity.PostSortType
import com.techtaurant.mainserver.post.enums.PostStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse

@Tag(name = "게시물", description = "게시물 API")
interface PostReadOpenApiControllerDocs {
    @Operation(
        summary = "게시물 목록 조회",
        description =
            "게시물 목록을 커서 기반 페이지네이션으로 조회합니다. " +
                "정적 콘텐츠와 함께 조회수/좋아요수/댓글수, 작성자 프로필 이미지, 썸네일을 한 응답에 담아 반환하며, " +
                "로그인 사용자에게는 읽음/좋아요/차단 상태가 함께 포함됩니다. " +
                "로그인 시 본인의 PRIVATE 게시물도 함께 조회됩니다. " +
                "keyword를 지정하면 제목 또는 본문에 검색어가 포함된 게시물만 대소문자 구분 없이 조회하며, " +
                "검색어의 `%`와 `_`는 와일드카드가 아닌 일반 문자로 취급합니다.",
    )
    @SwaggerApiResponse(
        responseCode = "200",
        description = "조회 성공 (작성자 프로필 이미지, 게시물 썸네일, 읽음 여부 포함)",
    )
    @ApiCommonBadRequestAndUnknown
    fun getPosts(
        @Parameter(description = "이전 응답의 nextCursor (첫 페이지는 생략)") cursor: String?,
        @Parameter(description = "페이지 크기 (1-100, 기본값 20)") @Min(1) @Max(100) size: Int,
        @Parameter(description = "기간 필터 (WEEK: 7일, MONTH: 30일, YEAR: 365일, ALL: 전체)") period: PostPeriod,
        @Parameter(description = "정렬 기준 (LATEST: 최신순, VIEW: 조회순, LIKE: 추천순, COMMENT: 댓글순)") sort: PostSortType,
        @Parameter(description = "작성자 ID 필터 (생략 시 전체 조회, 본인 조회 시 PRIVATE 포함)") authorId: UUID?,
        @Parameter(description = "카테고리 ID 필터 (authorId 지정 시에만 적용, 생략 시 전체)") categoryId: UUID?,
        @Parameter(description = "태그 UUID 필터 (여러 개 전달 시 OR 조건으로 조회)") tagIds: List<UUID>?,
        @Parameter(description = "검색어 (2-100자, 제목·본문 부분 일치)")
        @Size(min = 2, max = 100)
        @Pattern(regexp = "(?s).*\\S.*")
        keyword: String?,
        currentUserId: UUID?,
    ): ApiResponse<CursorPageResponse<PostListItemResponse>>

    @Operation(
        summary = "게시물 상세 조회",
        description =
            "게시물 상세 정보를 조회합니다. " +
                "정적 콘텐츠와 함께 카운트, 작성자 프로필 이미지, 첨부 presigned URL, 로그인 사용자의 읽음/좋아요 상태를 반환합니다. " +
                "DRAFT/PRIVATE 게시물은 작성자만 조회할 수 있습니다. " +
                "조회 로그는 이 API에서 기록하지 않으며, POST /open-api/posts/{postId}/view-logs API가 담당합니다.",
    )
    @SwaggerApiResponse(
        responseCode = "200",
        description = "조회 성공",
    )
    @ApiErrorCodeResponses(
        [
            ApiErrorCodeResponse(PostStatus::class, ["POST_NOT_FOUND"]),
            ApiErrorCodeResponse(DefaultStatus::class, ["UNKNOWN_EXCEPTION"]),
        ],
    )
    fun getPostDetail(
        @Parameter(description = "게시물 ID") postId: UUID,
        @Parameter(hidden = true) userId: UUID?,
    ): ApiResponse<PostDetailResponse>
}
