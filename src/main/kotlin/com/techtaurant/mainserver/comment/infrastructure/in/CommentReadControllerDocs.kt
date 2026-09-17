package com.techtaurant.mainserver.comment.infrastructure.`in`

import com.techtaurant.mainserver.comment.dto.CommentListResponse
import com.techtaurant.mainserver.comment.enums.CommentSortType
import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.dto.CursorPageResponse
import com.techtaurant.mainserver.common.swagger.ApiCommonBadRequestAndUnknown
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.util.UUID
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse

@Tag(name = "댓글", description = "댓글 API")
interface CommentReadControllerDocs {
    @Operation(
        summary = "부모 댓글 목록 조회",
        description =
            "게시물의 부모 댓글 목록을 커서 기반 페이지네이션으로 조회합니다. " +
                "댓글 본문과 함께 좋아요수/대댓글수/삭제 여부, 작성자 이름과 프로필 이미지를 한 응답에 담아 반환하며, " +
                "로그인 사용자에게는 좋아요 상태와 차단 여부가 함께 포함됩니다. " +
                "차단한 사용자의 댓글은 마스킹되어 반환됩니다.",
    )
    @SwaggerApiResponse(
        responseCode = "200",
        description = "조회 성공",
    )
    @ApiCommonBadRequestAndUnknown
    fun getParentComments(
        userId: UUID?,
        @Parameter(description = "게시물 ID") postId: UUID,
        @Parameter(description = "이전 응답의 nextCursor (첫 페이지는 생략)") cursor: String?,
        @Parameter(description = "페이지 크기 (1-100, 기본값 20)") @Min(1) @Max(100) size: Int,
        @Parameter(description = "정렬 기준 (LATEST: 최신순, LIKE: 추천순, REPLY: 답글순)") sort: CommentSortType,
    ): ApiResponse<CursorPageResponse<CommentListResponse>>

    @Operation(
        summary = "대댓글 목록 조회",
        description =
            "부모 댓글의 대댓글 목록을 커서 기반 페이지네이션으로 조회합니다. " +
                "댓글 본문과 함께 좋아요수/대댓글수/삭제 여부, 작성자 이름과 프로필 이미지를 한 응답에 담아 반환하며, " +
                "로그인 사용자에게는 좋아요 상태와 차단 여부가 함께 포함됩니다. " +
                "차단한 사용자의 댓글은 마스킹되어 반환됩니다.",
    )
    @SwaggerApiResponse(
        responseCode = "200",
        description = "조회 성공",
    )
    @ApiCommonBadRequestAndUnknown
    fun getReplies(
        userId: UUID?,
        @Parameter(description = "부모 댓글 ID") commentId: UUID,
        @Parameter(description = "이전 응답의 nextCursor (첫 페이지는 생략)") cursor: String?,
        @Parameter(description = "페이지 크기 (1-100, 기본값 20)") @Min(1) @Max(100) size: Int,
        @Parameter(description = "정렬 기준 (LATEST: 최신순, LIKE: 추천순, REPLY: 답글순)") sort: CommentSortType,
    ): ApiResponse<CursorPageResponse<CommentListResponse>>
}
