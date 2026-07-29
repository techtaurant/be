package com.techtaurant.mainserver.security.infrastructure.`in`

import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.status.DefaultStatus
import com.techtaurant.mainserver.common.swagger.ApiErrorCodeResponse
import com.techtaurant.mainserver.common.swagger.ApiErrorCodeResponses
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse

@Tag(name = "인증", description = "인증 API")
interface AuthApiControllerDocs {
    @Operation(summary = "로그아웃", description = "쿠키에서 토큰을 삭제하여 로그아웃합니다")
    @SwaggerApiResponse(
        responseCode = "200",
        description = "로그아웃 성공",
    )
    @ApiErrorCodeResponses(
        [
            ApiErrorCodeResponse(DefaultStatus::class, ["UNKNOWN_EXCEPTION"]),
        ],
    )
    fun logout(
        authenticatedUserId: UUID?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ApiResponse<Unit>
}
