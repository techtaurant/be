package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import com.techtaurant.mainserver.link.entity.LinkCrawlFailedJob
import com.techtaurant.mainserver.link.enums.LinkStatus
import org.jsoup.HttpStatusException
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

class LinkCrawlDocumentParser(
    private val linkDocumentFetcher: LinkDocumentFetcher,
) {
    fun fetchPageOrNull(pageUrl: String): Document? {
        return try {
            linkDocumentFetcher.fetch(pageUrl)
        } catch (exception: HttpStatusException) {
            null
        } catch (exception: Exception) {
            throw ApiException(
                LinkStatus.LINK_CRAWL_BATCH_NOT_CRAWLABLE,
                exception,
                exception.message ?: LinkStatus.LINK_CRAWL_BATCH_NOT_CRAWLABLE.getDescription(),
            )
        }
    }

    fun buildPageUrl(
        baseUrl: String,
        pageUriTemplate: String,
        page: Int,
    ): String {
        val pageUri = pageUriTemplate.replace("{page}", page.toString())
        return if (pageUri.startsWith("http://") || pageUri.startsWith("https://")) {
            pageUri
        } else {
            URI.create(baseUrl).resolve(pageUri).toString()
        }
    }

    fun extractArticleUrl(
        item: Element,
        batch: LinkCrawlBatch,
        pageUrl: String,
    ): String? {
        return extractArticleUrl(item, LinkCrawlSelectors.from(batch), pageUrl)
    }

    fun extractArticleUrl(
        item: Element,
        selectors: LinkCrawlSelectors,
        pageUrl: String,
    ): String? {
        val linkElement = resolveElement(item, selectors.articleLinkSelector) ?: return null
        val href = linkElement.attr("href").trim()
        if (href.isBlank()) {
            return null
        }

        return linkElement.absUrl("href").ifBlank {
            URI.create(pageUrl).resolve(href).toString()
        }
    }

    fun extractFailedJobDraft(
        item: Element,
        batch: LinkCrawlBatch,
        pageUrl: String,
    ): LinkFailedJobDraft? {
        return extractFailedJobDraft(item, LinkCrawlSelectors.from(batch), pageUrl)
    }

    fun extractFailedJobDraft(
        item: Element,
        selectors: LinkCrawlSelectors,
        pageUrl: String,
    ): LinkFailedJobDraft? {
        val articleUrl = extractArticleUrl(item, selectors, pageUrl) ?: return null
        val title =
            resolveText(item, selectors.titleSelector)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { title -> LinkCrawlFailedJob.truncateTitle(title) }
        val summary = selectors.summarySelector?.let { resolveText(item, it) }?.trim()?.takeIf(String::isNotEmpty)

        return LinkFailedJobDraft(
            articleUrl = articleUrl,
            title = title,
            summary = summary,
        )
    }

    fun extractSnapshot(
        item: Element,
        batch: LinkCrawlBatch,
        pageUrl: String,
    ): LinkSnapshot? {
        return extractSnapshot(item, LinkCrawlSelectors.from(batch), pageUrl)
    }

    fun extractSnapshot(
        item: Element,
        selectors: LinkCrawlSelectors,
        pageUrl: String,
    ): LinkSnapshot? {
        val absoluteUrl = extractArticleUrl(item, selectors, pageUrl) ?: return null

        val title =
            resolveText(item, selectors.titleSelector)
                ?.takeIf { it.isNotBlank() }
                ?: return null

        val summary = selectors.summarySelector?.let { resolveText(item, it) }.orEmpty()
        val createdAt =
            resolveCreatedAt(item, absoluteUrl, selectors)
                ?: throw ApiException(LinkStatus.LINK_CRAWL_BATCH_CREATED_AT_REQUIRED)

        return LinkSnapshot(
            title = title,
            url = absoluteUrl,
            summary = summary,
            createdAt = createdAt,
        )
    }

    fun parseCreatedAtFromArticlePage(
        articleUrl: String,
        createdAtSelectors: String?,
    ): Instant? {
        if (createdAtSelectors.isNullOrBlank()) {
            return null
        }

        val articleDocument = fetchPageOrNull(articleUrl) ?: return null
        return parseCreatedAt(firstResolvedValue(articleDocument, createdAtSelectors))
    }

    private fun resolveCreatedAt(
        item: Element,
        articleUrl: String,
        selectors: LinkCrawlSelectors,
    ): Instant? {
        val createdAtFromListItem = parseCreatedAt(firstResolvedValue(item, selectors.createdAtSelectors))
        if (createdAtFromListItem != null) {
            return createdAtFromListItem
        }

        return parseCreatedAtFromArticlePage(articleUrl, selectors.createdAtSelectors)
    }

    private fun resolveElement(
        root: Element,
        selector: String?,
    ): Element? {
        if (selector.isNullOrBlank()) {
            return null
        }

        return if (selector.trim() == ":self") {
            root
        } else {
            root.selectFirst(selector)
        }
    }

    private fun resolveText(
        root: Element,
        selector: String?,
    ): String? {
        return resolveElement(root, selector)?.text()?.trim()
    }

    private fun firstResolvedValue(
        root: Element,
        selectors: String?,
    ): String? {
        return selectors.toLineList()
            .mapNotNull { selector ->
                val element = resolveElement(root, selector)
                val value =
                    element?.attr("datetime")?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: element?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
                        ?: element?.text()?.trim()?.takeIf { it.isNotEmpty() }
                value
            }.firstOrNull()
    }

    private fun parseCreatedAt(rawValue: String?): Instant? {
        if (rawValue.isNullOrBlank()) {
            return null
        }

        return runCatching { Instant.parse(rawValue) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(rawValue).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(rawValue).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(rawValue).toInstant(ZoneOffset.UTC) }.getOrNull()
            ?: runCatching { LocalDate.parse(rawValue).atStartOfDay().toInstant(ZoneOffset.UTC) }.getOrNull()
            ?: parseAbsoluteDate(rawValue)
    }

    private fun parseAbsoluteDate(rawValue: String): Instant? {
        val match = ABSOLUTE_DATE_REGEX.matchEntire(rawValue) ?: return null
        val (year, month, day) = match.destructured

        return runCatching {
            LocalDate.of(year.toInt(), month.toInt(), day.toInt())
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC)
        }.getOrNull()
    }

    private fun String?.toLineList(): List<String> {
        return this?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toList()
            ?: emptyList()
    }

    private companion object {
        private val ABSOLUTE_DATE_REGEX = Regex("""^\s*(\d{4})\s*(?:[./-]|년)\s*(\d{1,2})\s*(?:[./-]|월)\s*(\d{1,2})\s*(?:일)?\s*\.?\s*$""")
    }
}
