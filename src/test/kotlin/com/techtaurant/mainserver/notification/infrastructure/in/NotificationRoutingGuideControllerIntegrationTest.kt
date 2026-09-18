package com.techtaurant.mainserver.notification.infrastructure.`in`

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.notification.enums.NotificationType
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

@DisplayName("NotificationRoutingGuideController 통합 테스트")
class NotificationRoutingGuideControllerIntegrationTest : IntegrationTest() {
    @Test
    @DisplayName("인증 없이 요청하면 모든 알림 타입의 이동 가이드를 HTML로 반환한다")
    fun getRoutingGuide_returnsHtmlCoveringEveryNotificationType() {
        val guideHtml =
            given()
                .`when`()
                .get("/open-api/docs/notifications")
                .then()
                .statusCode(HttpStatus.OK.value())
                .contentType(startsWith(MediaType.TEXT_HTML_VALUE))
                .extract()
                .asString()

        val notificationTypeNames = NotificationType.entries.map { it.name }
        assertThat(guideHtml).contains(notificationTypeNames)
    }
}
