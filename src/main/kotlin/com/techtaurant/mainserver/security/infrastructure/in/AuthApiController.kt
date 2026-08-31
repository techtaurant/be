package com.techtaurant.mainserver.security.infrastructure.`in`

import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.swagger.ApiErrorResponses
import com.techtaurant.mainserver.security.SecurityConstants
import com.techtaurant.mainserver.security.aop.AuthRestController
import com.techtaurant.mainserver.security.service.LogoutService
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import java.util.UUID

/**
 * refreshToken 쿠키는 /open-api/auth 아래에만 실리므로 이 경로에서는 폐기할 토큰을 특정할 수 없어
 * 인증된 사용자의 세션을 모두 끊습니다. 기기별 로그아웃은 AuthOpenApiController가 담당하며,
 * 클라이언트가 그쪽으로 옮기고 나면 이 경로는 제거합니다.
 */
@AuthRestController
@RequestMapping("${SecurityConstants.API_PREFIX}/auth")
class AuthApiController(
    private val logoutService: LogoutService,
) : AuthApiControllerDocs {
    @ApiErrorResponses(includeAuthenticationErrors = true)
    @PostMapping("/logout")
    override fun logout(
        @AuthenticationPrincipal authenticatedUserId: UUID,
        response: HttpServletResponse,
    ): ApiResponse<Unit> {
        logoutService.logoutAllDevices(authenticatedUserId, response)
        return ApiResponse.ok(Unit)
    }
}
