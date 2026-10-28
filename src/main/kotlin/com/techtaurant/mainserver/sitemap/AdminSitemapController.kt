package com.techtaurant.mainserver.sitemap

import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.swagger.ApiErrorResponses
import com.techtaurant.mainserver.security.SecurityConstants
import io.swagger.v3.oas.annotations.Operation
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@ConditionalOnProperty("sitemap.enabled", havingValue = "true")
class AdminSitemapController(private val service: SitemapGenerationService) {
    @Operation(summary = "사이트맵을 생성해 S3에 게시한다", description = "관리자 전용 동기 실행. 실행 잠금이 있으면 409를 반환합니다.")
    @ApiErrorResponses(includeAuthenticationErrors = true)
    @PostMapping("${SecurityConstants.ADMIN_API_PREFIX}/sitemap/runs")
    fun generate(): ApiResponse<SitemapGenerationResult> = ApiResponse.ok(service.generate())
}
