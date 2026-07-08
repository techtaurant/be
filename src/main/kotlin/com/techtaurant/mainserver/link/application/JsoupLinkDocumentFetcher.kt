package com.techtaurant.mainserver.link.application

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

@Component
class JsoupLinkDocumentFetcher(
    private val playwrightLinkDocumentFetcher: PlaywrightLinkDocumentFetcher,
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
