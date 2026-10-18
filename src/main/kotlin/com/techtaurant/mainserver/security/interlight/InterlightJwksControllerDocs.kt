package com.techtaurant.mainserver.security.interlight

import com.techtaurant.mainserver.common.swagger.ApiErrorCodeResponse
import com.techtaurant.mainserver.common.swagger.ApiErrorCodeResponses
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse

@Tag(name = "Interlight 인증", description = "면접 앱 서버가 사용하는 전용 토큰 검증용 공개키")
interface InterlightJwksControllerDocs {
    @Operation(summary = "Interlight 공개키 조회", description = "인증 없이 현재 및 이전 RSA 공개키를 표준 JWKS로 반환합니다. 개인키와 토큰은 반환하지 않습니다")
    @SwaggerApiResponse(responseCode = "200", description = "표준 JWKS 공개키 집합")
    @ApiErrorCodeResponses([ApiErrorCodeResponse(InterlightJwtStatus::class, ["KEYS_UNAVAILABLE"])])
    fun jwks(): InterlightJwksResponse
}
