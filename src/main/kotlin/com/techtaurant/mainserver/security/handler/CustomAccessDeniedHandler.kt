package com.techtaurant.mainserver.security.handler

import com.fasterxml.jackson.databind.ObjectMapper
import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.status.StatusMessageResolver
import com.techtaurant.mainserver.security.jwt.JwtStatus
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component

/**
 * 인증된 사용자가 권한이 없는 리소스에 접근할 때 호출되는 핸들러
 * Spring Security 필터 체인에서 인가 실패 시 공통 에러 응답을 반환
 */
@Component
class CustomAccessDeniedHandler(
    private val objectMapper: ObjectMapper,
    private val statusMessageResolver: StatusMessageResolver,
) : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        val jwtStatus = JwtStatus.ACCESS_DENIED
        val errorResponse = ApiResponse.error<Any>(jwtStatus, statusMessageResolver.resolve(jwtStatus, request))

        response.status = jwtStatus.getHttpStatusCode()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write(objectMapper.writeValueAsString(errorResponse))
    }
}
