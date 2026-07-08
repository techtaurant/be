package com.techtaurant.mainserver.link.application

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

@Primary
@Component
class JsoupLinkDocumentFetcher(
    @Qualifier("playwrightLinkDocumentFetcher")
    private val playwrightLinkDocumentFetcher: LinkDocumentFetcher,
) : LinkDocumentFetcher {
    override fun fetch(url: String): Document {
        return try {
            Jsoup.connect(url).get()
        } catch (jsoupException: Exception) {
            fetchWithPlaywright(url, jsoupException)
        }
    }

    private fun fetchWithPlaywright(
        url: String,
        jsoupException: Exception,
    ): Document {
        return try {
            playwrightLinkDocumentFetcher.fetch(url)
        } catch (playwrightException: Exception) {
            playwrightException.addSuppressed(jsoupException)
            throw playwrightException
        }
    }
}
