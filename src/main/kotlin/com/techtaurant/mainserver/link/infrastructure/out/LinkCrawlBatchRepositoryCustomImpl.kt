package com.techtaurant.mainserver.link.infrastructure.out

import com.github.f4b6a3.uuid.UuidCreator
import com.techtaurant.mainserver.jooq.tables.LinkCrawlBatches.Companion.LINK_CRAWL_BATCHES
import com.techtaurant.mainserver.jooq.tables.records.LinkCrawlBatchesRecord
import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

@Repository
class LinkCrawlBatchRepositoryCustomImpl(
    private val dsl: DSLContext,
) : LinkCrawlBatchRepository {
    override fun save(batch: LinkCrawlBatch): LinkCrawlBatch {
        val id = batch.id ?: UuidCreator.getTimeOrderedEpoch().also { batch.id = it }
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        dsl.insertInto(LINK_CRAWL_BATCHES)
            .set(LINK_CRAWL_BATCHES.ID, id).set(LINK_CRAWL_BATCHES.COMPANY_USER_ID, requireNotNull(batch.companyUser.id))
            .set(LINK_CRAWL_BATCHES.NAME, batch.name).set(LINK_CRAWL_BATCHES.BASE_URL, batch.baseUrl)
            .set(LINK_CRAWL_BATCHES.PAGE_URI_TEMPLATE, batch.pageUriTemplate).set(LINK_CRAWL_BATCHES.ITEM_SELECTOR, batch.itemSelector)
            .set(
                LINK_CRAWL_BATCHES.ARTICLE_LINK_SELECTOR,
                batch.articleLinkSelector,
            ).set(LINK_CRAWL_BATCHES.TITLE_SELECTOR, batch.titleSelector)
            .set(
                LINK_CRAWL_BATCHES.SUMMARY_SELECTOR,
                batch.summarySelector,
            ).set(LINK_CRAWL_BATCHES.PUBLISHED_AT_SELECTORS, batch.createdAtSelectors)
            .set(LINK_CRAWL_BATCHES.TAG_NAMES, batch.tagNames).set(LINK_CRAWL_BATCHES.CRON_EXPRESSION, batch.cronExpression)
            .set(LINK_CRAWL_BATCHES.START_PAGE, batch.startPage).set(LINK_CRAWL_BATCHES.END_PAGE, batch.endPage)
            .set(
                LINK_CRAWL_BATCHES.ACTIVE,
                batch.active,
            ).set(LINK_CRAWL_BATCHES.LAST_TRIGGERED_AT_UTC, batch.lastTriggeredAt?.atOffset(ZoneOffset.UTC))
            .set(LINK_CRAWL_BATCHES.CREATED_AT_UTC, batch.createdAt.atOffset(ZoneOffset.UTC)).set(LINK_CRAWL_BATCHES.UPDATED_AT_UTC, now)
            .onConflict(LINK_CRAWL_BATCHES.ID).doUpdate()
            .set(LINK_CRAWL_BATCHES.NAME, batch.name).set(LINK_CRAWL_BATCHES.BASE_URL, batch.baseUrl)
            .set(LINK_CRAWL_BATCHES.PAGE_URI_TEMPLATE, batch.pageUriTemplate).set(LINK_CRAWL_BATCHES.ITEM_SELECTOR, batch.itemSelector)
            .set(
                LINK_CRAWL_BATCHES.ARTICLE_LINK_SELECTOR,
                batch.articleLinkSelector,
            ).set(LINK_CRAWL_BATCHES.TITLE_SELECTOR, batch.titleSelector)
            .set(
                LINK_CRAWL_BATCHES.SUMMARY_SELECTOR,
                batch.summarySelector,
            ).set(LINK_CRAWL_BATCHES.PUBLISHED_AT_SELECTORS, batch.createdAtSelectors)
            .set(LINK_CRAWL_BATCHES.TAG_NAMES, batch.tagNames).set(LINK_CRAWL_BATCHES.CRON_EXPRESSION, batch.cronExpression)
            .set(LINK_CRAWL_BATCHES.START_PAGE, batch.startPage).set(LINK_CRAWL_BATCHES.END_PAGE, batch.endPage)
            .set(
                LINK_CRAWL_BATCHES.ACTIVE,
                batch.active,
            ).set(LINK_CRAWL_BATCHES.LAST_TRIGGERED_AT_UTC, batch.lastTriggeredAt?.atOffset(ZoneOffset.UTC))
            .set(LINK_CRAWL_BATCHES.UPDATED_AT_UTC, now).execute()
        batch.updatedAt = now.toInstant()
        return batch
    }

    override fun saveAndFlush(batch: LinkCrawlBatch): LinkCrawlBatch = save(batch)

    override fun findAll(): List<LinkCrawlBatch> = dsl.selectFrom(LINK_CRAWL_BATCHES).fetch().map { it.toLinkCrawlBatch() }

    override fun findById(id: UUID): Optional<LinkCrawlBatch> =
        Optional.ofNullable(dsl.selectFrom(LINK_CRAWL_BATCHES).where(LINK_CRAWL_BATCHES.ID.eq(id)).fetchOne()?.toLinkCrawlBatch())

    override fun existsById(id: UUID): Boolean = dsl.fetchExists(LINK_CRAWL_BATCHES, LINK_CRAWL_BATCHES.ID.eq(id))

    override fun findAllByCompanyUserId(companyUserId: UUID): List<LinkCrawlBatch> =
        dsl.selectFrom(LINK_CRAWL_BATCHES)
            .where(LINK_CRAWL_BATCHES.COMPANY_USER_ID.eq(companyUserId))
            .fetch()
            .map { it.toLinkCrawlBatch() }

    override fun findAllByActiveTrue(): List<LinkCrawlBatch> =
        dsl.selectFrom(LINK_CRAWL_BATCHES)
            .where(LINK_CRAWL_BATCHES.ACTIVE.isTrue)
            .fetch()
            .map { it.toLinkCrawlBatch() }

    private fun LinkCrawlBatchesRecord.toLinkCrawlBatch(): LinkCrawlBatch =
        LinkCrawlBatch(
            companyUser = userReference(requireNotNull(companyUserId)),
            name = requireNotNull(name),
            baseUrl = requireNotNull(baseUrl),
            pageUriTemplate = requireNotNull(pageUriTemplate),
            itemSelector = requireNotNull(itemSelector),
            articleLinkSelector = requireNotNull(articleLinkSelector),
            titleSelector = requireNotNull(titleSelector),
            summarySelector = summarySelector,
            createdAtSelectors = publishedAtSelectors,
            tagNames = tagNames,
            cronExpression = requireNotNull(cronExpression),
            startPage = requireNotNull(startPage),
            endPage = requireNotNull(endPage),
            active = requireNotNull(active),
            lastTriggeredAt = lastTriggeredAtUtc?.toInstant(),
        ).apply {
            id = requireNotNull(this@toLinkCrawlBatch.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }

    private fun userReference(userId: UUID): User = User("", "", OAuthProvider.GOOGLE, "", UserRole.USER, "").apply { id = userId }
}
