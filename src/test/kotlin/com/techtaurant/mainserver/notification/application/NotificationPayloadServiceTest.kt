package com.techtaurant.mainserver.notification.application

import com.techtaurant.mainserver.config.MessageSourceConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Locale

class NotificationPayloadServiceTest {
    private lateinit var notificationPayloadService: NotificationPayloadService

    @BeforeEach
    fun setUp() {
        notificationPayloadService = NotificationPayloadService(MessageSourceConfig().messageSource())
    }

    @Test
    @DisplayName("댓글 알림 payload는 이미지 없이 텍스트만 포함한다")
    fun buildPayload_returnsCommentMessageHtmlOnly() {
        val payload =
            notificationPayloadService.buildPayload(
                messageKey = "notification.payload.post-comment",
                messageArguments = listOf("민수", "JPA 정리"),
                locale = Locale.KOREAN,
            )

        assertThat(payload).doesNotContain("<img", "https://cdn.example.com/profile.png", "민수 프로필 이미지")
        assertThat(payload).contains("<strong>민수</strong>")
        assertThat(payload).contains("<strong>JPA 정리</strong>")
        assertThat(payload).contains("댓글을 남겼습니다")
    }

    @Test
    @DisplayName("새 글 알림 payload는 게시물 썸네일 없이 텍스트만 포함한다")
    fun buildPayload_returnsFollowerPostMessageHtmlOnly() {
        val payload =
            notificationPayloadService.buildPayload(
                messageKey = "notification.payload.follower-post",
                messageArguments = listOf("Minsu", "DDD Start"),
                locale = Locale.ENGLISH,
            )

        assertThat(payload).doesNotContain("<img", "/static/images/post-thumbnail.png", "DDD Start 썸네일")
        assertThat(payload).contains("<strong>Minsu</strong>")
        assertThat(payload).contains("<strong>DDD Start</strong>")
        assertThat(payload).contains("published a new post")
    }

    @Test
    @DisplayName("payload 생성 시 동적 값을 제거하지 않고 포함한다")
    fun buildPayload_preservesArguments() {
        val payload =
            notificationPayloadService.buildPayload(
                messageKey = "notification.payload.follow",
                messageArguments = listOf("<script>alert('xss')</script><b>민수 & 2 < 3 > 1</b>"),
                locale = Locale.KOREAN,
            )

        assertThat(payload).contains("<script>alert('xss')</script><b>민수 & 2 < 3 > 1</b>")
        assertThat(payload).doesNotContain("&amp;", "&lt;", "&gt;")
        assertThat(payload).contains("민수")
        assertThat(payload).contains("팔로우했습니다")
    }

    @Test
    @DisplayName("안전한 media URL은 알림 썸네일 URL로 반환한다")
    fun resolveThumbnailUrl_returnsSafeMediaUrl() {
        val safeThumbnailUrl =
            notificationPayloadService.resolveThumbnailUrl(
                NotificationPayloadService.NotificationPayloadMedia(url = "https://cdn.example.com/profile.png"),
            )

        assertThat(safeThumbnailUrl).isEqualTo("https://cdn.example.com/profile.png")
    }

    @Test
    @DisplayName("media URL은 백엔드에서 차단하지 않고 그대로 반환한다")
    fun resolveThumbnailUrl_returnsMediaUrlAsIs() {
        val thumbnailUrl =
            notificationPayloadService.resolveThumbnailUrl(
                NotificationPayloadService.NotificationPayloadMedia(url = "javascript:alert('xss')"),
            )

        assertThat(thumbnailUrl).isEqualTo("javascript:alert('xss')")
    }
}
