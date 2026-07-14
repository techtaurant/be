package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.jooq.tables.LinkCrawlBatches.Companion.LINK_CRAWL_BATCHES
import com.techtaurant.mainserver.jooq.tables.records.LinkCrawlBatchesRecord
import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.util.Optional
import java.util.UUID

@Repository
class LinkCrawlBatchRepositoryCustomImpl(
    private val dsl: DSLContext,
) : LinkCrawlBatchRepositoryCustom {
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
