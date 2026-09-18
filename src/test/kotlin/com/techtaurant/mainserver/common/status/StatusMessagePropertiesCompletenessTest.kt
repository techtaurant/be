package com.techtaurant.mainserver.common.status

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Properties

@DisplayName("에러 상태 메시지 번역 파일 완전성 테스트")
class StatusMessagePropertiesCompletenessTest {
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["messages.properties", "messages_en.properties", "messages_zh.properties", "messages_ja.properties"])
    @DisplayName("모든 에러 상태 코드는 언어별 메시지 파일마다 비어 있지 않은 번역을 가진다")
    fun everyErrorStatus_hasTranslationInEachMessageFile(messageFileName: String) {
        // given
        val errorStatuses =
            scanStatusEnums()
                .flatMap { statusEnum -> statusEnum.enumConstants.toList() }
                .filter { it.getHttpStatusCode() >= ERROR_HTTP_STATUS_CODE_THRESHOLD }
        val messages = loadMessageFile(messageFileName)

        // when
        val untranslatedStatusCodes =
            errorStatuses
                .map { StatusMessageResolver.messageKeyOf(it) }
                .filter { messageKey -> messages.getProperty(messageKey).isNullOrBlank() }

        // then
        assertThat(errorStatuses)
            .describedAs("에러 상태를 스캔하지 못하면 번역 누락 검사가 무의미해진다")
            .hasSizeGreaterThanOrEqualTo(KNOWN_ERROR_STATUS_COUNT)
        assertThat(untranslatedStatusCodes)
            .describedAs("$messageFileName 에 번역이 없으면 해당 언어 사용자에게 다른 언어 메시지가 나간다")
            .isEmpty()
    }

    private fun loadMessageFile(messageFileName: String): Properties {
        val messageStream = requireNotNull(javaClass.classLoader.getResourceAsStream(messageFileName)) { "$messageFileName 이 없습니다" }
        return messageStream.reader(Charsets.UTF_8).use { reader -> Properties().apply { load(reader) } }
    }

    companion object {
        private const val ERROR_HTTP_STATUS_CODE_THRESHOLD = 400
        private const val KNOWN_ERROR_STATUS_COUNT = 54
    }
}
