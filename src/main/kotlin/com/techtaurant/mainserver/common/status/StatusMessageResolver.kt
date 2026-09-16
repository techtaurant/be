package com.techtaurant.mainserver.common.status

import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.MessageSource
import org.springframework.stereotype.Component
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver
import java.util.Locale

/**
 * 요청의 Accept-Language 헤더에 맞춰 상태 코드의 응답 메시지를 번역한다.
 * 보안 필터처럼 DispatcherServlet을 거치지 않는 응답도 같은 언어 규칙을 따르도록 요청에서 직접 언어를 정하며,
 * 지원하지 않는 언어이거나 헤더가 없으면 영어로 응답한다.
 */
@Component
class StatusMessageResolver(
    private val messageSource: MessageSource,
) {
    private val acceptLanguageLocaleResolver =
        AcceptHeaderLocaleResolver().apply {
            supportedLocales = SUPPORTED_RESPONSE_LOCALES
            setDefaultLocale(Locale.ENGLISH)
        }

    fun resolve(
        status: StatusIfs,
        request: HttpServletRequest,
    ): String {
        val responseLocale = acceptLanguageLocaleResolver.resolveLocale(request)
        return messageSource.getMessage(messageKeyOf(status), null, responseLocale)
    }

    companion object {
        private val SUPPORTED_RESPONSE_LOCALES = listOf(Locale.KOREAN, Locale.ENGLISH, Locale.CHINESE, Locale.JAPANESE)

        internal fun messageKeyOf(status: StatusIfs): String = "status.${status.getCustomStatusCode()}"
    }
}
