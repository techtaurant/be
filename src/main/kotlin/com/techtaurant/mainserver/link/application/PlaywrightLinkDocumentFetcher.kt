package com.techtaurant.mainserver.link.application

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.TimeoutError
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitUntilState
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

@Component
class PlaywrightLinkDocumentFetcher {
    fun fetch(url: String): Document {
        val playwright = Playwright.create()
        try {
            val browser =
                playwright.chromium()
                    .launch(BrowserType.LaunchOptions().setHeadless(true))
            try {
                return fetchWithBrowser(browser, url)
            } finally {
                browser.close()
            }
        } finally {
            playwright.close()
        }
    }

    private fun fetchWithBrowser(
        browser: Browser,
        url: String,
    ): Document {
        val page = browser.newPage()
        try {
            val response =
                page.navigate(
                    url,
                    Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.LOAD)
                        .setTimeout(NAVIGATION_TIMEOUT_MILLIS),
                )

            if (response != null && response.status() >= HTTP_ERROR_STATUS_CODE) {
                throw HttpStatusException("HTTP error fetching URL", response.status(), url)
            }

            waitForRenderedContent(page)
            return Jsoup.parse(page.content(), url)
        } finally {
            page.close()
        }
    }

    private fun waitForRenderedContent(page: Page) {
        try {
            page.waitForLoadState(
                LoadState.NETWORKIDLE,
                Page.WaitForLoadStateOptions().setTimeout(RENDER_IDLE_TIMEOUT_MILLIS),
            )
        } catch (exception: TimeoutError) {
            return
        }
    }

    private companion object {
        private const val HTTP_ERROR_STATUS_CODE = 400
        private const val NAVIGATION_TIMEOUT_MILLIS = 30_000.0
        private const val RENDER_IDLE_TIMEOUT_MILLIS = 5_000.0
    }
}
