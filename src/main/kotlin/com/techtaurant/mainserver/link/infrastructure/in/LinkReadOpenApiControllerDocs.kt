package com.techtaurant.mainserver.link.infrastructure.`in`

import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.dto.CursorPageResponse
import com.techtaurant.mainserver.common.swagger.ApiCommonBadRequestAndUnknown
import com.techtaurant.mainserver.common.swagger.ApiErrorCodeResponse
import com.techtaurant.mainserver.common.swagger.ApiErrorCodeResponses
import com.techtaurant.mainserver.link.dto.LinkContentDetailResponse
import com.techtaurant.mainserver.link.dto.LinkContentListItemResponse
import com.techtaurant.mainserver.link.enums.LinkPeriod
import com.techtaurant.mainserver.link.enums.LinkSortType
import com.techtaurant.mainserver.link.enums.LinkStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.util.UUID
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse

@Tag(name = "링크", description = "링크 API")
interface LinkReadOpenApiControllerDocs {
    @Operation(
        summary = "링크 정적 콘텐츠 목록 조회",
        description =
            "SSG/ISR 캐싱에 적합한 링크 정적 콘텐츠 목록을 명시적 정렬/기간 기준으로 조회합니다. " +
                "누적 통계(조회수/좋아요수/저장수 합계)를 함께 반환하며, 통계 이력이 없는 링크는 0으로 채워집니다. " +
                "로그인 사용자의 저장/읽음 상태는 포함하지 않습니다.\n\n" +
                "정렬(sort)\n" +
                "- PUBLISHED: 기간 내 링크 생성일 최신순\n" +
                "- LIKE: 기간 내 일별 좋아요 집계 합 기준\n" +
                "- SAVE: 기간 내 일별 저장 집계 합 기준\n\n" +
                "기간(period)은 PUBLISHED 정렬의 링크 생성일 필터와 LIKE/SAVE 정렬의 일별 집계 윈도우를 결정합니다. " +
                "LIKE/SAVE 정렬은 해당 기간 내 좋아요/저장 기록이 있는 링크만 포함합니다. " +
                "nextCursor는 요청한 정렬에 종속되며, 다른 정렬로 이어서 요청하면 INVALID_LINK_CURSOR를 반환합니다.",
    )
    @SwaggerApiResponse(responseCode = "200", description = "조회 성공")
    @ApiCommonBadRequestAndUnknown
    @ApiErrorCodeResponses(
        [
            ApiErrorCodeResponse(LinkStatus::class, ["INVALID_LINK_CURSOR"]),
        ],
    )
    fun getLinkContents(
        @Parameter(description = "이전 응답의 nextCursor (첫 페이지는 생략)") cursor: String?,
        @Parameter(description = "페이지 크기 (1-100, 기본값 20)") @Min(1) @Max(100) size: Int,
        @Parameter(description = "정렬 기준 (PUBLISHED: 생성일순, LIKE: 좋아요순, SAVE: 저장순)") sort: LinkSortType,
        @Parameter(description = "기간 필터 (WEEK: 7일, MONTH: 30일, YEAR: 365일, ALL: 전체)") period: LinkPeriod,
        @Parameter(description = "출처 회사 사용자 ID 필터 (생략 시 전체 조회)") sourceCompanyUserId: UUID?,
        @Parameter(description = "링크 태그명 필터 (생략 시 전체 조회)") tag: String?,
    ): ApiResponse<CursorPageResponse<LinkContentListItemResponse>>

    @Operation(
        summary = "링크 정적 콘텐츠 상세 조회",
        description =
            "SSG/ISR 캐싱에 적합한 링크 정적 콘텐츠 상세 정보를 조회합니다. " +
                "누적 통계(조회수/좋아요수/저장수 합계)를 함께 반환하며, 통계 이력이 없으면 0으로 채워집니다. " +
                "로그인 사용자의 저장/읽음 상태는 포함하지 않습니다.",
    )
    @SwaggerApiResponse(responseCode = "200", description = "조회 성공")
    @ApiErrorCodeResponses(
        [
            ApiErrorCodeResponse(LinkStatus::class, ["LINK_NOT_FOUND"]),
        ],
    )
    fun getLinkContentDetail(
        @Parameter(description = "링크 ID") linkId: UUID,
    ): ApiResponse<LinkContentDetailResponse>
}
