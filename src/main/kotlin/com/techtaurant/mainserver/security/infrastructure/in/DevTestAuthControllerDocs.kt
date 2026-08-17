package com.techtaurant.mainserver.security.infrastructure.`in`

import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.status.DefaultStatus
import com.techtaurant.mainserver.common.swagger.ApiErrorCodeResponse
import com.techtaurant.mainserver.common.swagger.ApiErrorCodeResponses
import com.techtaurant.mainserver.security.dto.DevTestLoginRequest
import com.techtaurant.mainserver.security.dto.DevTestLoginResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse

@Tag(name = "개발 인증", description = "개발 환경 전용 인증 API")
interface DevTestAuthControllerDocs {
    @Operation(
        summary = "개발용 테스트 로그인",
        description = "테스트 사용자로 로그인하여 JWT 토큰을 쿠키로 발급받습니다. 사용자가 없으면 자동 생성되며, 요청 role 값으로 USER 또는 ADMIN 권한을 설정할 수 있습니다.",
    )
    @SwaggerApiResponse(
        responseCode = "200",
        description = "로그인 성공, 쿠키 및 응답 body에 accessToken/refreshToken 설정",
    )
    @ApiErrorCodeResponses(
        [
            ApiErrorCodeResponse(DefaultStatus::class, ["BAD_REQUEST", "SERVER_ERROR", "UNKNOWN_EXCEPTION"]),
        ],
    )
    fun login(
        request: DevTestLoginRequest,
        httpRequest: HttpServletRequest,
        response: HttpServletResponse,
    ): ApiResponse<DevTestLoginResponse>
}
