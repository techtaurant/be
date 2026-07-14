package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.jooq.tables.LinkCrawlBatches.Companion.LINK_CRAWL_BATCHES
import com.techtaurant.mainserver.jooq.tables.LinkCrawlRuns.Companion.LINK_CRAWL_RUNS
import com.techtaurant.mainserver.jooq.tables.records.LinkCrawlBatchesRecord
import com.techtaurant.mainserver.jooq.tables.records.LinkCrawlRunsRecord
import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import com.techtaurant.mainserver.link.entity.LinkCrawlRun
import com.techtaurant.mainserver.link.enums.LinkCrawlRunStatus
import com.techtaurant.mainserver.link.enums.LinkCrawlRunTriggerType
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.util.Optional
import java.util.UUID

@Repository
class LinkCrawlRunRepositoryCustomImpl(
    private val dsl: DSLContext,
) : LinkCrawlRunRepositoryCustom {
    override fun findById(id: UUID): Optional<LinkCrawlRun> =
        Optional.ofNullable(
            dsl.select(LINK_CRAWL_RUNS.asterisk(), LINK_CRAWL_BATCHES.asterisk())
                .from(LINK_CRAWL_RUNS)
                .join(LINK_CRAWL_BATCHES).on(LINK_CRAWL_RUNS.BATCH_ID.eq(LINK_CRAWL_BATCHES.ID))
                .where(LINK_CRAWL_RUNS.ID.eq(id))
                .fetchOne()
                ?.let { record -> record.into(LINK_CRAWL_RUNS).toLinkCrawlRun(record.into(LINK_CRAWL_BATCHES).toLinkCrawlBatch()) },
        )

    override fun existsById(id: UUID): Boolean = dsl.fetchExists(LINK_CRAWL_RUNS, LINK_CRAWL_RUNS.ID.eq(id))

    override fun findAllByBatchIdOrderByStartedAtDesc(batchId: UUID): List<LinkCrawlRun> =
        dsl.selectFrom(LINK_CRAWL_RUNS)
            .where(LINK_CRAWL_RUNS.BATCH_ID.eq(batchId))
            .orderBy(LINK_CRAWL_RUNS.STARTED_AT_UTC.desc())
            .fetch()
            .map { it.toLinkCrawlRun() }

    private fun LinkCrawlRunsRecord.toLinkCrawlRun(batch: LinkCrawlBatch = batchReference(requireNotNull(batchId))): LinkCrawlRun =
        LinkCrawlRun(
            batch = batch,
            triggerType = LinkCrawlRunTriggerType.valueOf(requireNotNull(triggerType)),
            status = LinkCrawlRunStatus.valueOf(requireNotNull(status)),
            collectedCount = requireNotNull(collectedCount),
            newLinkCount = requireNotNull(newLinkCount),
            existingLinkCount = requireNotNull(existingLinkCount),
            skippedCount = requireNotNull(skippedCount),
            failedJobCount = requireNotNull(failedJobCount),
            errorStatusCode = errorStatusCode,
            errorMessage = errorMessage,
            startedAt = requireNotNull(startedAtUtc).toInstant(),
            finishedAt = requireNotNull(finishedAtUtc).toInstant(),
        ).apply {
            id = requireNotNull(this@toLinkCrawlRun.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }

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

    private fun batchReference(batchId: UUID): LinkCrawlBatch =
        LinkCrawlBatch(
            companyUser = User("", "", OAuthProvider.GOOGLE, "", UserRole.USER, ""),
            name = "",
            baseUrl = "",
            pageUriTemplate = "",
            itemSelector = "",
            articleLinkSelector = "",
            titleSelector = "",
            cronExpression = "",
        ).apply { id = batchId }

    private fun userReference(userId: UUID): User = User("", "", OAuthProvider.GOOGLE, "", UserRole.USER, "").apply { id = userId }
}
