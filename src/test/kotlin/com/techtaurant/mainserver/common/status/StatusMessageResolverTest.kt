package com.techtaurant.mainserver.common.status

import com.techtaurant.mainserver.config.MessageSourceConfig
import com.techtaurant.mainserver.post.enums.PostStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest

@DisplayName("StatusMessageResolver 테스트")
class StatusMessageResolverTest {
    private val statusMessageResolver = StatusMessageResolver(MessageSourceConfig().messageSource())

    @ParameterizedTest(name = "Accept-Language={0} → {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "ko-KR | 게시물을 찾을 수 없습니다",
            "en-US | Post not found",
            "zh-CN | 找不到该帖子",
            "zh-TW | 找不到该帖子",
            "ja-JP | 投稿が見つかりません",
            "fr-FR | Post not found",
            "'fr, ja;q=0.8' | 投稿が見つかりません",
        ],
    )
    @DisplayName("Accept-Language 헤더의 지원 언어로 메시지를 돌려주고, 지원하지 않는 언어는 영어로 돌려준다")
    fun resolve_returnsMessageInRequestedLanguage(
        acceptLanguage: String,
        expectedMessage: String,
    ) {
        // given
        val request = MockHttpServletRequest().apply { addHeader(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage) }

        // when
        val message = statusMessageResolver.resolve(PostStatus.POST_NOT_FOUND, request)

        // then
        assertThat(message).isEqualTo(expectedMessage)
    }

    @Test
    @DisplayName("Accept-Language 헤더가 없으면 영어 메시지를 돌려준다")
    fun resolve_withoutAcceptLanguage_returnsEnglishMessage() {
        // given
        val request = MockHttpServletRequest()

        // when
        val message = statusMessageResolver.resolve(PostStatus.POST_NOT_FOUND, request)

        // then
        assertThat(message).isEqualTo("Post not found")
    }
}
