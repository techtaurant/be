package com.techtaurant.mainserver.security.infrastructure.`in`

import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.swagger.ApiErrorResponses
import com.techtaurant.mainserver.security.SecurityConstants
import com.techtaurant.mainserver.security.aop.AuthRestController
import com.techtaurant.mainserver.security.jwt.JwtStatus
import com.techtaurant.mainserver.security.service.LogoutService
import com.techtaurant.mainserver.security.service.TokenRefreshService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping

@AuthRestController
@RequestMapping("${SecurityConstants.OPEN_API_PREFIX}/auth")
class AuthOpenApiController(
    private val tokenRefreshService: TokenRefreshService,
    private val logoutService: LogoutService,
) : AuthOpenApiControllerDocs {
    @ApiErrorResponses(
        jwts = [
            JwtStatus.MISSING_REFRESH_TOKEN,
            JwtStatus.REFRESH_TOKEN_EXPIRED,
            JwtStatus.INVALID_REFRESH_TOKEN,
            JwtStatus.MALFORMED_TOKEN,
            JwtStatus.UNSUPPORTED_TOKEN,
            JwtStatus.INVALID_TOKEN,
        ],
    )
    @PostMapping("/refresh")
    override fun refresh(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ApiResponse<Unit> {
        tokenRefreshService.execute(request = request, response = response)
        return ApiResponse.ok()
    }

    @PostMapping("/logout")
    override fun logout(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ApiResponse<Unit> {
        logoutService.logoutCurrentDevice(request, response)
        return ApiResponse.ok(Unit)
    }
}
