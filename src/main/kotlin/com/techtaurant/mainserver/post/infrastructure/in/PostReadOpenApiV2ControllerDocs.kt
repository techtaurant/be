package com.techtaurant.mainserver.post.infrastructure.`in`

import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.dto.CursorPageResponse
import com.techtaurant.mainserver.common.status.DefaultStatus
import com.techtaurant.mainserver.common.swagger.ApiCommonBadRequestAndUnknown
import com.techtaurant.mainserver.common.swagger.ApiErrorCodeResponse
import com.techtaurant.mainserver.common.swagger.ApiErrorCodeResponses
import com.techtaurant.mainserver.post.dto.PostContentDetailResponse
import com.techtaurant.mainserver.post.dto.PostContentListItemResponse
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
interface PostReadOpenApiV2ControllerDocs {
    @Operation(
        summary = "게시물 정적 콘텐츠 목록 조회",
        description =
            "SSG/ISR 캐싱에 적합한 게시물 정적 콘텐츠 목록을 조회합니다. " +
                "조회수, 좋아요수, 댓글수, 상태, 썸네일/첨부 presigned URL은 " +
                "GET /open-api/posts/metadatas?postIds=... API로, 작성자 이름과 프로필 이미지는 " +
                "GET /open-api/users/profile-images?userIds=... API로 분리되었습니다. " +
                "로그인 사용자의 읽음/좋아요/차단 상태는 GET /api/posts/me/states?postIds=... API를 사용하세요. " +
                "keyword를 지정하면 제목 또는 본문에 검색어가 포함된 게시물만 대소문자 구분 없이 조회하며, " +
                "검색어의 `%`와 `_`는 와일드카드가 아닌 일반 문자로 취급합니다.",
    )
    @SwaggerApiResponse(
        responseCode = "200",
        description = "조회 성공",
    )
    @ApiCommonBadRequestAndUnknown
    fun getPostContents(
        @Parameter(description = "이전 응답의 nextCursor (첫 페이지는 생략)") cursor: String?,
        @Parameter(description = "페이지 크기 (1-100, 기본값 20)") @Min(1) @Max(100) size: Int,
        @Parameter(description = "기간 필터 (WEEK: 7일, MONTH: 30일, YEAR: 365일, ALL: 전체)") period: PostPeriod,
        @Parameter(description = "정렬 기준 (LATEST: 최신순, VIEW: 조회순, LIKE: 추천순, COMMENT: 댓글순)") sort: PostSortType,
        @Parameter(description = "작성자 ID 필터 (생략 시 전체 조회)") authorId: UUID?,
        @Parameter(description = "카테고리 ID 필터 (생략 시 전체)") categoryId: UUID?,
        @Parameter(description = "태그 UUID 필터 (여러 개 전달 시 OR 조건으로 조회)") tagIds: List<UUID>?,
        @Parameter(description = "검색어 (2-100자, 제목·본문 부분 일치)")
        @Size(min = 2, max = 100)
        @Pattern(regexp = "(?s).*\\S.*")
        keyword: String?,
    ): ApiResponse<CursorPageResponse<PostContentListItemResponse>>

    @Operation(
        summary = "게시물 정적 콘텐츠 상세 조회",
        description =
            "SSG/ISR 캐싱에 적합한 게시물 정적 콘텐츠 상세 정보를 조회합니다. " +
                "PUBLISHED 게시물만 조회하며 조회수 기록을 남기지 않습니다. " +
                "동적 metadata는 GET /open-api/posts/metadatas?postIds=..., 작성자 이름과 프로필 이미지는 " +
                "GET /open-api/users/profile-images?userIds=..., 조회 로그 기록은 " +
                "POST /open-api/posts/{postId}/view-logs API를 사용하세요.",
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
    fun getPostContentDetail(
        @Parameter(description = "게시물 ID") postId: UUID,
    ): ApiResponse<PostContentDetailResponse>
}
