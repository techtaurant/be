package com.techtaurant.mainserver.security.infrastructure.`in`

import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.status.DefaultStatus
import com.techtaurant.mainserver.common.swagger.ApiErrorCodeResponse
import com.techtaurant.mainserver.common.swagger.ApiErrorCodeResponses
import com.techtaurant.mainserver.security.jwt.JwtStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse

@Tag(name = "인증", description = "인증 API")
interface AuthApiControllerDocs {
    @Operation(
        summary = "로그아웃 (deprecated)",
        description =
            "이 사용자의 모든 기기 세션을 폐기하고 인증 쿠키를 삭제합니다. " +
                "기기별 로그아웃은 POST /open-api/auth/logout을 사용하세요",
        deprecated = true,
    )
    @SwaggerApiResponse(
        responseCode = "200",
        description = "로그아웃 성공",
    )
    @ApiErrorCodeResponses(
        [
            ApiErrorCodeResponse(JwtStatus::class, ["AUTHENTICATION_REQUIRED"]),
            ApiErrorCodeResponse(DefaultStatus::class, ["UNKNOWN_EXCEPTION"]),
        ],
    )
    fun logout(
        authenticatedUserId: UUID,
        response: HttpServletResponse,
    ): ApiResponse<Unit>
}
