package com.techtaurant.mainserver.notification.application

import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.stereotype.Service
import java.util.Locale

@Service
class NotificationPayloadService(
    private val messageSource: MessageSource,
) {
    fun buildPayload(
        messageKey: String,
        messageArguments: List<String>,
        locale: Locale? = null,
    ): String {
        val messageHtml = buildMessage(messageKey, locale, messageArguments)

        return "<div><span>$messageHtml</span></div>"
    }

    fun resolveThumbnailUrl(media: NotificationPayloadMedia): String = media.url

    private fun buildMessage(
        key: String,
        locale: Locale?,
        args: List<String>,
    ): String {
        val resolvedLocale = locale ?: LocaleContextHolder.getLocale()
        val localizedMessage = messageSource.getMessage(key, args.toTypedArray(), resolvedLocale)

        return localizedMessage.trim()
    }

    data class NotificationPayloadMedia(
        val url: String,
    )
}
