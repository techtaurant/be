package com.techtaurant.mainserver.notification.infrastructure.`in`

import com.techtaurant.mainserver.security.SecurityConstants
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets

/**
 * 개발 환경 전용 알림 이동 가이드 컨트롤러
 *
 * prod 프로파일과 app.environment=prod가 아닌 환경에서 FE가 알림 타입별 이동 위치를 연동할 때 참고할 HTML 가이드를 제공한다.
 */
@RestController
@Profile("!prod")
@ConditionalOnExpression("!'\${app.environment:dev}'.trim().equalsIgnoreCase('prod')")
@RequestMapping("${SecurityConstants.OPEN_API_PREFIX}/docs")
class NotificationRoutingGuideController : NotificationRoutingGuideControllerDocs {
    // static/ 아래에 두면 prod에서도 정적 리소스로 공개되므로 classpath의 docs/ 경로에 둔다.
    private val routingGuideHtml: String =
        ClassPathResource(ROUTING_GUIDE_HTML_PATH).getContentAsString(StandardCharsets.UTF_8)

    @GetMapping("/notifications")
    override fun getRoutingGuide(): ResponseEntity<String> =
        ResponseEntity.ok()
            .contentType(MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
            .body(routingGuideHtml)

    private companion object {
        const val ROUTING_GUIDE_HTML_PATH = "docs/notification-routing-guide.html"
    }
}
