package com.techtaurant.mainserver.user.infrastructure.`in`

import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.dto.CursorPageResponse
import com.techtaurant.mainserver.common.swagger.ApiCommonBadRequestAndUnknown
import com.techtaurant.mainserver.post.dto.PostContentListItemResponse
import com.techtaurant.mainserver.post.entity.PostPeriod
import com.techtaurant.mainserver.post.entity.PostSortType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.util.UUID
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse

@Tag(name = "사용자", description = "사용자 Open API")
interface UserPostOpenApiV2ControllerDocs {
    @Operation(
        summary = "사용자 게시물 정적 콘텐츠 목록 조회",
        description =
            "[Deprecated] 기존 클라이언트 호환을 위한 경로입니다. 신규 연동은 " +
                "GET /open-api/v2/posts?authorId=... API를 사용하세요. " +
                "조회수, 좋아요수, 댓글수, 상태, 썸네일/첨부 presigned URL은 " +
                "GET /open-api/posts/metadatas?postIds=... API로, 작성자 이름과 프로필 이미지는 " +
                "GET /open-api/users/profile-images?userIds=... API로 분리되었습니다. " +
                "로그인 사용자의 읽음/좋아요/차단 상태는 GET /api/posts/me/states?postIds=... API를 사용하세요.",
        deprecated = true,
    )
    @SwaggerApiResponse(
        responseCode = "200",
        description = "조회 성공",
    )
    @ApiCommonBadRequestAndUnknown
    fun getPostContentsByUserId(
        @Parameter(description = "조회 대상 사용자 ID") userId: UUID,
        @Parameter(description = "이전 응답의 nextCursor (첫 페이지는 생략)") cursor: String?,
        @Parameter(description = "페이지 크기 (1-100, 기본값 20)") @Min(1) @Max(100) size: Int,
        @Parameter(description = "기간 필터 (WEEK: 7일, MONTH: 30일, YEAR: 365일, ALL: 전체)") period: PostPeriod,
        @Parameter(description = "정렬 기준 (LATEST: 최신순, VIEW: 조회순, LIKE: 추천순, COMMENT: 댓글순)") sort: PostSortType,
        @Parameter(description = "카테고리 ID 필터 (생략 시 전체)") categoryId: UUID?,
    ): ApiResponse<CursorPageResponse<PostContentListItemResponse>>
}
