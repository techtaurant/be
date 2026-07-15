package com.techtaurant.mainserver.link.infrastructure.out

import com.github.f4b6a3.uuid.UuidCreator
import com.techtaurant.mainserver.jooq.tables.LinkCrawlRuns.Companion.LINK_CRAWL_RUNS
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
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

@Repository
class LinkCrawlRunRepositoryCustomImpl(
    private val dsl: DSLContext,
    private val linkCrawlBatchRepository: LinkCrawlBatchRepository,
) : LinkCrawlRunRepositoryCustom {
    override fun save(run: LinkCrawlRun): LinkCrawlRun {
        val id = run.id ?: UuidCreator.getTimeOrderedEpoch().also { run.id = it }
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        dsl.insertInto(LINK_CRAWL_RUNS)
            .set(LINK_CRAWL_RUNS.ID, id).set(LINK_CRAWL_RUNS.BATCH_ID, requireNotNull(run.batch.id))
            .set(LINK_CRAWL_RUNS.TRIGGER_TYPE, run.triggerType.name).set(LINK_CRAWL_RUNS.STATUS, run.status.name)
            .set(LINK_CRAWL_RUNS.COLLECTED_COUNT, run.collectedCount).set(LINK_CRAWL_RUNS.NEW_LINK_COUNT, run.newLinkCount)
            .set(LINK_CRAWL_RUNS.EXISTING_LINK_COUNT, run.existingLinkCount).set(LINK_CRAWL_RUNS.SKIPPED_COUNT, run.skippedCount)
            .set(LINK_CRAWL_RUNS.FAILED_JOB_COUNT, run.failedJobCount).set(LINK_CRAWL_RUNS.ERROR_STATUS_CODE, run.errorStatusCode)
            .set(
                LINK_CRAWL_RUNS.ERROR_MESSAGE,
                run.errorMessage,
            ).set(LINK_CRAWL_RUNS.STARTED_AT_UTC, run.startedAt.atOffset(ZoneOffset.UTC))
            .set(LINK_CRAWL_RUNS.FINISHED_AT_UTC, run.finishedAt.atOffset(ZoneOffset.UTC))
            .set(LINK_CRAWL_RUNS.CREATED_AT_UTC, run.createdAt.atOffset(ZoneOffset.UTC)).set(LINK_CRAWL_RUNS.UPDATED_AT_UTC, now)
            .onConflict(LINK_CRAWL_RUNS.ID).doUpdate()
            .set(LINK_CRAWL_RUNS.STATUS, run.status.name).set(LINK_CRAWL_RUNS.COLLECTED_COUNT, run.collectedCount)
            .set(LINK_CRAWL_RUNS.NEW_LINK_COUNT, run.newLinkCount).set(LINK_CRAWL_RUNS.EXISTING_LINK_COUNT, run.existingLinkCount)
            .set(LINK_CRAWL_RUNS.SKIPPED_COUNT, run.skippedCount).set(LINK_CRAWL_RUNS.FAILED_JOB_COUNT, run.failedJobCount)
            .set(LINK_CRAWL_RUNS.ERROR_STATUS_CODE, run.errorStatusCode).set(LINK_CRAWL_RUNS.ERROR_MESSAGE, run.errorMessage)
            .set(
                LINK_CRAWL_RUNS.FINISHED_AT_UTC,
                run.finishedAt.atOffset(ZoneOffset.UTC),
            ).set(LINK_CRAWL_RUNS.UPDATED_AT_UTC, now).execute()
        run.updatedAt = now.toInstant()
        linkCrawlBatchRepository.save(run.batch)
        return run
    }

    override fun findById(id: UUID): Optional<LinkCrawlRun> =
        Optional.ofNullable(
            dsl.selectFrom(LINK_CRAWL_RUNS)
                .where(LINK_CRAWL_RUNS.ID.eq(id))
                .fetchOne()
                ?.let { record ->
                    record.toLinkCrawlRun(
                        linkCrawlBatchRepository.findById(requireNotNull(record.batchId)).orElseThrow(),
                    )
                },
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
}
