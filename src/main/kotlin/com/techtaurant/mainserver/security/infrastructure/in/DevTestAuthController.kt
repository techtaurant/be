package com.techtaurant.mainserver.security.infrastructure.`in`

import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.status.DefaultStatus
import com.techtaurant.mainserver.common.swagger.ApiErrorResponses
import com.techtaurant.mainserver.security.SecurityConstants
import com.techtaurant.mainserver.security.dto.DevTestLoginRequest
import com.techtaurant.mainserver.security.dto.DevTestLoginResponse
import com.techtaurant.mainserver.security.service.DevTestAuthService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 개발 환경 전용 테스트 인증 컨트롤러
 *
 * prod 프로파일과 app.environment=prod가 아닌 환경에서 테스트 사용자로 JWT 토큰을 발급받을 수 있다.
 */
@RestController
@Profile("!prod")
@ConditionalOnExpression("!'\${app.environment:dev}'.trim().equalsIgnoreCase('prod')")
@RequestMapping("${SecurityConstants.OPEN_API_PREFIX}/dev/auth")
class DevTestAuthController(
    private val devTestAuthService: DevTestAuthService,
) : DevTestAuthControllerDocs {
    @ApiErrorResponses(
        defaults = [DefaultStatus.BAD_REQUEST, DefaultStatus.SERVER_ERROR],
        includeValidationError = true,
    )
    @PostMapping("/login")
    override fun login(
        @RequestBody @Valid request: DevTestLoginRequest,
        httpRequest: HttpServletRequest,
        response: HttpServletResponse,
    ): ApiResponse<DevTestLoginResponse> {
        return ApiResponse.ok(devTestAuthService.execute(request, httpRequest, response))
    }
}
