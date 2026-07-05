package com.techtaurant.mainserver.link.infrastructure.`in`

import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.status.DefaultStatus
import com.techtaurant.mainserver.common.swagger.ApiErrorCodeResponse
import com.techtaurant.mainserver.common.swagger.ApiErrorCodeResponses
import com.techtaurant.mainserver.link.dto.RecordLinkLikeRequest
import com.techtaurant.mainserver.link.dto.RecordLinkReadRequest
import com.techtaurant.mainserver.link.enums.LinkStatus
import com.techtaurant.mainserver.security.jwt.JwtStatus
import com.techtaurant.mainserver.user.enums.UserStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import java.util.UUID
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse

@Tag(name = "링크", description = "링크 사용자 상호작용 API")
interface LinkControllerDocs {
    @Operation(summary = "링크 저장", description = "사용자가 링크를 저장합니다. 이미 저장된 경우에도 멱등하게 처리합니다")
    fun saveLink(
        userId: UUID,
        @Parameter(description = "링크 ID") linkId: UUID,
    ): ApiResponse<Unit>

    @Operation(summary = "링크 저장 취소", description = "사용자가 저장한 링크를 해제합니다")
    fun unsaveLink(
        userId: UUID,
        @Parameter(description = "링크 ID") linkId: UUID,
    )

    @Operation(summary = "링크 읽음 상태 변경", description = "링크 읽음/안읽음 상태를 명시적으로 토글합니다")
    fun toggleReadStatus(
        userId: UUID,
        @Parameter(description = "링크 ID") linkId: UUID,
        @Valid request: RecordLinkReadRequest,
    ): ApiResponse<Unit>

    @Operation(
        summary = "링크 좋아요 상태 변경",
        description = "링크에 대한 좋아요 상태를 변경합니다. NONE: 취소, LIKE: 좋아요, DISLIKE: 싫어요. 인증된 사용자만 호출 가능합니다.",
    )
    @SwaggerApiResponse(responseCode = "200", description = "좋아요/싫어요 기록 성공")
    @ApiErrorCodeResponses(
        [
            ApiErrorCodeResponse(JwtStatus::class, ["AUTHENTICATION_REQUIRED"]),
            ApiErrorCodeResponse(LinkStatus::class, ["LINK_NOT_FOUND"]),
            ApiErrorCodeResponse(UserStatus::class, ["ID_NOT_FOUND"]),
            ApiErrorCodeResponse(DefaultStatus::class, ["UNKNOWN_EXCEPTION"]),
        ],
    )
    fun recordLike(
        userId: UUID,
        @Parameter(description = "링크 ID") linkId: UUID,
        @Valid request: RecordLinkLikeRequest,
    ): ApiResponse<Unit>
}
