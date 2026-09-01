package com.techtaurant.mainserver.security.infrastructure.`in`

import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.status.DefaultStatus
import com.techtaurant.mainserver.common.swagger.ApiErrorCodeResponse
import com.techtaurant.mainserver.common.swagger.ApiErrorCodeResponses
import com.techtaurant.mainserver.security.jwt.JwtStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse

@Tag(name = "인증", description = "인증 API")
interface AuthApiControllerDocs {
    @Operation(
        summary = "로그아웃 (deprecated)",
        description =
            "인증 쿠키만 삭제합니다. 서버에 남은 로그인 기록은 만료될 때까지 유지되므로, " +
                "그 기기의 세션까지 끊으려면 POST /open-api/auth/logout을 사용하세요",
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
    fun logout(response: HttpServletResponse): ApiResponse<Unit>
}
