package com.techtaurant.mainserver.link.infrastructure.`in`

import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.dto.CursorPageResponse
import com.techtaurant.mainserver.common.swagger.ApiCommonBadRequestAndUnknown
import com.techtaurant.mainserver.common.swagger.ApiErrorCodeResponse
import com.techtaurant.mainserver.common.swagger.ApiErrorCodeResponses
import com.techtaurant.mainserver.link.dto.LinkContentListItemResponse
import com.techtaurant.mainserver.link.enums.LinkStatus
import com.techtaurant.mainserver.user.enums.UserStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.util.UUID
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse

@Tag(name = "링크", description = "링크 API")
interface CompanyLinkReadOpenApiControllerDocs {
    @Operation(
        summary = "회사 링크 목록 조회",
        description =
            "회사가 수집한 링크 목록을 생성일 최신순 커서 기반으로 조회합니다. " +
                "누적 통계(조회수/좋아요수/저장수 합계)를 함께 반환하며, 통계 이력이 없는 링크는 0으로 채워집니다.",
    )
    @SwaggerApiResponse(responseCode = "200", description = "회사 링크 목록 조회 성공")
    @ApiCommonBadRequestAndUnknown
    @ApiErrorCodeResponses(
        [
            ApiErrorCodeResponse(UserStatus::class, ["COMPANY_NOT_FOUND"]),
            ApiErrorCodeResponse(LinkStatus::class, ["INVALID_LINK_CURSOR"]),
        ],
    )
    fun getCompanyLinkContents(
        @Parameter(description = "회사 사용자 ID") companyUserId: UUID,
        @Parameter(description = "이전 응답의 nextCursor (첫 페이지는 생략)") cursor: String?,
        @Parameter(description = "페이지 크기 (1-100, 기본값 20)") @Min(1) @Max(100) size: Int,
    ): ApiResponse<CursorPageResponse<LinkContentListItemResponse>>
}
