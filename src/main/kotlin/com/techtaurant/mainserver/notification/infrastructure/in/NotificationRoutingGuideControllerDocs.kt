package com.techtaurant.mainserver.notification.infrastructure.`in`

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse

@Tag(name = "개발 알림 가이드", description = "개발 환경 전용 알림 연동 가이드 API")
interface NotificationRoutingGuideControllerDocs {
    @Operation(
        summary = "알림 타입별 이동 위치 연동 가이드",
        description = "알림 목록 아이템을 클릭했을 때 type과 arguments를 보고 FE가 어느 화면으로 이동해야 하는지 설명하는 HTML을 반환합니다.",
    )
    @SwaggerApiResponse(
        responseCode = "200",
        description = "알림 이동 가이드 HTML",
        content = [Content(mediaType = MediaType.TEXT_HTML_VALUE)],
    )
    fun getRoutingGuide(): ResponseEntity<String>
}
