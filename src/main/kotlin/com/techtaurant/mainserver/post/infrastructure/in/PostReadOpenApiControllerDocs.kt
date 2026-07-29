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
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.util.UUID
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse

@Tag(name = "게시물", description = "게시물 API")
interface PostReadOpenApiControllerDocs {
    @Operation(
        summary = "게시물 목록 조회",
        description =
            "[Deprecated] 이 API는 정적 콘텐츠, 공개 동적 메타데이터, 로그인 사용자 상태가 하나의 응답에 섞여 있습니다. " +
                "정적 콘텐츠 목록은 GET /open-api/v2/posts, 공개 동적 메타데이터는 GET /open-api/posts/metadatas?postIds=..., " +
                "작성자 이름과 프로필 이미지는 GET /open-api/users/profile-images?userIds=..., 로그인 사용자 상태는 " +
                "GET /api/posts/me/states?postIds=... API로 대체되었습니다. " +
                "기존 호환을 위해 커서 기반 페이지네이션과 로그인 시 본인의 PRIVATE 게시물 포함 동작은 유지됩니다.",
        deprecated = true,
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
        @Parameter(description = "작성자 ID 필터 (생략 시 전체 조회, 본인 조회 시 DRAFT/PRIVATE 포함)") authorId: UUID?,
        @Parameter(description = "카테고리 ID 필터 (authorId 지정 시에만 적용, 생략 시 전체)") categoryId: UUID?,
        @Parameter(description = "태그 UUID 필터 (여러 개 전달 시 OR 조건으로 조회)") tagIds: List<UUID>?,
        currentUserId: UUID?,
    ): ApiResponse<CursorPageResponse<PostListItemResponse>>

    @Operation(
        summary = "게시물 상세 조회",
        description =
            "[Deprecated] 이 API는 정적 콘텐츠, 공개 동적 메타데이터, 로그인 사용자 상태가 하나의 응답에 섞여 있으며 조회 시 조회 로그가 기록됩니다. " +
                "정적 상세 콘텐츠는 GET /open-api/v2/posts/{postId}, 공개 동적 메타데이터는 GET /open-api/posts/metadatas?postIds=..., " +
                "작성자 이름과 프로필 이미지는 GET /open-api/users/profile-images?userIds=..., 로그인 사용자 상태는 " +
                "GET /api/posts/me/states?postIds=... API로 대체되었습니다. " +
                "조회 로그만 분리 기록해야 하는 경우 POST /open-api/posts/{postId}/view-logs API를 사용할 수 있습니다.",
        deprecated = true,
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
        @Parameter(hidden = true) request: HttpServletRequest,
        @Parameter(hidden = true) userId: UUID?,
    ): ApiResponse<PostDetailResponse>
}
