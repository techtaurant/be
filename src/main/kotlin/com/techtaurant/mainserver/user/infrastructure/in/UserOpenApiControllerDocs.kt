package com.techtaurant.mainserver.user.infrastructure.`in`

import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.dto.CursorPageResponse
import com.techtaurant.mainserver.common.status.DefaultStatus
import com.techtaurant.mainserver.common.swagger.ApiCommonBadRequestAndUnknown
import com.techtaurant.mainserver.common.swagger.ApiErrorCodeResponse
import com.techtaurant.mainserver.common.swagger.ApiErrorCodeResponses
import com.techtaurant.mainserver.post.dto.PostListItemResponse
import com.techtaurant.mainserver.post.entity.PostPeriod
import com.techtaurant.mainserver.post.entity.PostSortType
import com.techtaurant.mainserver.user.dto.UserFollowCountResponse
import com.techtaurant.mainserver.user.dto.UserFollowListItemResponse
import com.techtaurant.mainserver.user.dto.UserProfileImageResponse
import com.techtaurant.mainserver.user.dto.UserResponse
import com.techtaurant.mainserver.user.enums.UserStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse

@Tag(name = "사용자", description = "사용자 Open API")
interface UserOpenApiControllerDocs {
    @Operation(summary = "사용자 검색", description = "사용자 이름으로 검색합니다")
    @SwaggerApiResponse(
        responseCode = "200",
        description = "검색 성공",
    )
    @ApiErrorCodeResponses(
        [
            ApiErrorCodeResponse(DefaultStatus::class, ["BAD_REQUEST"]),
            ApiErrorCodeResponse(DefaultStatus::class, ["UNKNOWN_EXCEPTION"]),
        ],
    )
    fun searchByName(
        @Parameter(description = "검색할 사용자 이름 (1자 이상)")
        @NotBlank
        name: String,
    ): ApiResponse<List<UserResponse>>

    @Operation(
        summary = "사용자 프로필 표시 데이터 목록 조회",
        description =
            "userIds에 해당하는 사용자의 작성자 이름과 프로필 이미지 URL을 batch로 조회합니다. " +
                "존재하지 않는 사용자는 응답에서 제외됩니다.",
    )
    @SwaggerApiResponse(
        responseCode = "200",
        description = "조회 성공",
    )
    @ApiCommonBadRequestAndUnknown
    fun getUserProfileImages(
        @Parameter(description = "작성자 이름과 프로필 이미지 URL을 조회할 사용자 ID 목록 (최대 100개)", required = true)
        @Size(max = 100)
        userIds: List<UUID>,
    ): ApiResponse<List<UserProfileImageResponse>>

    @Operation(summary = "사용자 팔로워 수/팔로우 수 조회", description = "특정 사용자의 팔로워 수와 팔로우 수를 조회합니다")
    @SwaggerApiResponse(
        responseCode = "200",
        description = "조회 성공",
    )
    @ApiErrorCodeResponses(
        [
            ApiErrorCodeResponse(UserStatus::class, ["USER_NOT_FOUND"]),
            ApiErrorCodeResponse(DefaultStatus::class, ["UNKNOWN_EXCEPTION"]),
        ],
    )
    fun getFollowCounts(
        @Parameter(description = "조회 대상 사용자 ID") userId: UUID,
    ): ApiResponse<UserFollowCountResponse>

    @Operation(summary = "사용자 팔로잉 목록 조회", description = "특정 사용자가 팔로우한 사용자 목록을 최신순으로 조회합니다")
    @SwaggerApiResponse(
        responseCode = "200",
        description = "조회 성공",
    )
    @ApiErrorCodeResponses(
        [
            ApiErrorCodeResponse(UserStatus::class, ["USER_NOT_FOUND"]),
            ApiErrorCodeResponse(DefaultStatus::class, ["UNKNOWN_EXCEPTION"]),
        ],
    )
    fun getFollowings(
        @Parameter(description = "조회 대상 사용자 ID") userId: UUID,
    ): ApiResponse<List<UserFollowListItemResponse>>

    @Operation(summary = "사용자 팔로워 목록 조회", description = "특정 사용자를 팔로우하는 사용자 목록을 최신순으로 조회합니다")
    @SwaggerApiResponse(
        responseCode = "200",
        description = "조회 성공",
    )
    @ApiErrorCodeResponses(
        [
            ApiErrorCodeResponse(UserStatus::class, ["USER_NOT_FOUND"]),
            ApiErrorCodeResponse(DefaultStatus::class, ["UNKNOWN_EXCEPTION"]),
        ],
    )
    fun getFollowers(
        @Parameter(description = "조회 대상 사용자 ID") userId: UUID,
    ): ApiResponse<List<UserFollowListItemResponse>>

    @Operation(
        summary = "사용자 게시물 목록 조회",
        description =
            "특정 사용자가 작성한 게시물 목록을 커서 기반 페이지네이션으로 조회합니다. " +
                "정적 콘텐츠와 함께 조회수/좋아요수/댓글수, 작성자 프로필 이미지, 썸네일을 한 응답에 담아 반환합니다. " +
                "본인을 조회하면 PRIVATE 게시물도 함께 포함되고, 타인을 조회하면 PUBLISHED만 반환됩니다. " +
                "DRAFT를 포함한 내 게시물 관리 목록은 GET /api/users/me/posts API를 사용하세요.",
    )
    @SwaggerApiResponse(
        responseCode = "200",
        description = "조회 성공",
    )
    @ApiCommonBadRequestAndUnknown
    fun getPostsByUserId(
        @Parameter(description = "조회 대상 사용자 ID") userId: UUID,
        @Parameter(description = "이전 응답의 nextCursor (첫 페이지는 생략)") cursor: String?,
        @Parameter(description = "페이지 크기 (1-100, 기본값 20)") @Min(1) @Max(100) size: Int,
        @Parameter(description = "기간 필터 (WEEK: 7일, MONTH: 30일, YEAR: 365일, ALL: 전체)") period: PostPeriod,
        @Parameter(description = "정렬 기준 (LATEST: 최신순, VIEW: 조회순, LIKE: 추천순, COMMENT: 댓글순)") sort: PostSortType,
        @Parameter(description = "카테고리 ID 필터 (생략 시 전체)") categoryId: UUID?,
        currentUserId: UUID?,
    ): ApiResponse<CursorPageResponse<PostListItemResponse>>
}
