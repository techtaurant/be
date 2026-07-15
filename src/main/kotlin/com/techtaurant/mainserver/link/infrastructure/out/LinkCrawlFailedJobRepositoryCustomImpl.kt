package com.techtaurant.mainserver.link.infrastructure.out

import com.github.f4b6a3.uuid.UuidCreator
import com.techtaurant.mainserver.jooq.tables.LinkCrawlBatches.Companion.LINK_CRAWL_BATCHES
import com.techtaurant.mainserver.jooq.tables.LinkCrawlFailedJobs.Companion.LINK_CRAWL_FAILED_JOBS
import com.techtaurant.mainserver.jooq.tables.LinkCrawlRuns.Companion.LINK_CRAWL_RUNS
import com.techtaurant.mainserver.jooq.tables.records.LinkCrawlFailedJobsRecord
import com.techtaurant.mainserver.link.entity.LinkCrawlFailedJob
import com.techtaurant.mainserver.link.entity.LinkCrawlRun
import org.jooq.DSLContext
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

@Repository
class LinkCrawlFailedJobRepositoryCustomImpl(
    private val dsl: DSLContext,
    private val linkCrawlRunRepository: LinkCrawlRunRepository,
) : LinkCrawlFailedJobRepositoryCustom {
    override fun save(job: LinkCrawlFailedJob): LinkCrawlFailedJob {
        val id = job.id ?: UuidCreator.getTimeOrderedEpoch().also { job.id = it }
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        dsl.insertInto(LINK_CRAWL_FAILED_JOBS)
            .set(LINK_CRAWL_FAILED_JOBS.ID, id).set(LINK_CRAWL_FAILED_JOBS.RUN_ID, requireNotNull(job.run.id))
            .set(LINK_CRAWL_FAILED_JOBS.ARTICLE_URL, job.articleUrl).set(LINK_CRAWL_FAILED_JOBS.ERROR_STATUS_CODE, job.errorStatusCode)
            .set(LINK_CRAWL_FAILED_JOBS.ERROR_MESSAGE, job.errorMessage).set(LINK_CRAWL_FAILED_JOBS.FAILURE_COUNT, job.failureCount)
            .set(LINK_CRAWL_FAILED_JOBS.RESOLVED_AT_UTC, job.resolvedAt?.atOffset(ZoneOffset.UTC))
            .set(LINK_CRAWL_FAILED_JOBS.LAST_FAILED_AT_UTC, job.lastFailedAt.atOffset(ZoneOffset.UTC))
            .set(
                LINK_CRAWL_FAILED_JOBS.CREATED_AT_UTC,
                job.createdAt.atOffset(ZoneOffset.UTC),
            ).set(LINK_CRAWL_FAILED_JOBS.UPDATED_AT_UTC, now)
            .onConflict(LINK_CRAWL_FAILED_JOBS.ID).doUpdate()
            .set(LINK_CRAWL_FAILED_JOBS.ERROR_STATUS_CODE, job.errorStatusCode).set(LINK_CRAWL_FAILED_JOBS.ERROR_MESSAGE, job.errorMessage)
            .set(
                LINK_CRAWL_FAILED_JOBS.FAILURE_COUNT,
                job.failureCount,
            ).set(LINK_CRAWL_FAILED_JOBS.RESOLVED_AT_UTC, job.resolvedAt?.atOffset(ZoneOffset.UTC))
            .set(
                LINK_CRAWL_FAILED_JOBS.LAST_FAILED_AT_UTC,
                job.lastFailedAt.atOffset(ZoneOffset.UTC),
            ).set(LINK_CRAWL_FAILED_JOBS.UPDATED_AT_UTC, now).execute()
        job.updatedAt = now.toInstant()
        return job
    }

    override fun findById(id: UUID): Optional<LinkCrawlFailedJob> =
        Optional.ofNullable(
            dsl.selectFrom(LINK_CRAWL_FAILED_JOBS).where(LINK_CRAWL_FAILED_JOBS.ID.eq(id)).fetchOne()?.toLinkCrawlFailedJob(),
        )

    override fun findAllByRunIdAndResolvedAtIsNullOrderByCreatedAtAsc(runId: UUID): List<LinkCrawlFailedJob> = unresolvedJobs(runId)

    override fun findAllByRunIdAndResolvedAtIsNullOrderByCreatedAtAsc(
        runId: UUID,
        pageable: Pageable,
    ): List<LinkCrawlFailedJob> = unresolvedJobs(runId, pageable)

    override fun countByRunIdAndResolvedAtIsNull(runId: UUID): Long =
        dsl.fetchCount(
            LINK_CRAWL_FAILED_JOBS,
            LINK_CRAWL_FAILED_JOBS.RUN_ID.eq(runId).and(LINK_CRAWL_FAILED_JOBS.RESOLVED_AT_UTC.isNull),
        ).toLong()

    override fun findRetryableAutomaticJobs(
        maxFailureCount: Int,
        retryableBefore: Instant,
        pageable: Pageable,
    ): List<LinkCrawlFailedJob> =
        dsl.select(LINK_CRAWL_FAILED_JOBS.fields().toList())
            .from(LINK_CRAWL_FAILED_JOBS)
            .join(LINK_CRAWL_RUNS).on(LINK_CRAWL_FAILED_JOBS.RUN_ID.eq(LINK_CRAWL_RUNS.ID))
            .join(LINK_CRAWL_BATCHES).on(LINK_CRAWL_RUNS.BATCH_ID.eq(LINK_CRAWL_BATCHES.ID))
            .where(
                LINK_CRAWL_FAILED_JOBS.RESOLVED_AT_UTC.isNull
                    .and(LINK_CRAWL_BATCHES.ACTIVE.isTrue)
                    .and(LINK_CRAWL_FAILED_JOBS.FAILURE_COUNT.lt(maxFailureCount))
                    .and(LINK_CRAWL_FAILED_JOBS.LAST_FAILED_AT_UTC.le(OffsetDateTime.ofInstant(retryableBefore, ZoneOffset.UTC))),
            ).orderBy(LINK_CRAWL_FAILED_JOBS.CREATED_AT_UTC.asc())
            .limit(pageable.pageSize)
            .offset(pageable.offset)
            .fetch()
            .map { it.into(LINK_CRAWL_FAILED_JOBS).toLinkCrawlFailedJob() }

    override fun existsByRunIdAndResolvedAtIsNull(runId: UUID): Boolean =
        dsl.fetchExists(
            LINK_CRAWL_FAILED_JOBS,
            LINK_CRAWL_FAILED_JOBS.RUN_ID.eq(runId).and(LINK_CRAWL_FAILED_JOBS.RESOLVED_AT_UTC.isNull),
        )

    override fun findByRunIdAndArticleUrl(
        runId: UUID,
        articleUrl: String,
    ): LinkCrawlFailedJob? =
        dsl.selectFrom(LINK_CRAWL_FAILED_JOBS)
            .where(LINK_CRAWL_FAILED_JOBS.RUN_ID.eq(runId).and(LINK_CRAWL_FAILED_JOBS.ARTICLE_URL.eq(articleUrl)))
            .fetchOne()
            ?.toLinkCrawlFailedJob()

    override fun findRunIdsWithUnresolvedJobs(runIds: Collection<UUID>): Set<UUID> =
        if (runIds.isEmpty()) {
            emptySet()
        } else {
            dsl.selectDistinct(LINK_CRAWL_FAILED_JOBS.RUN_ID)
                .from(LINK_CRAWL_FAILED_JOBS)
                .where(LINK_CRAWL_FAILED_JOBS.RUN_ID.`in`(runIds).and(LINK_CRAWL_FAILED_JOBS.RESOLVED_AT_UTC.isNull))
                .fetchSet(LINK_CRAWL_FAILED_JOBS.RUN_ID)
                .filterNotNull()
                .toSet()
        }

    private fun unresolvedJobs(
        runId: UUID,
        pageable: Pageable? = null,
    ): List<LinkCrawlFailedJob> {
        val run = linkCrawlRunRepository.findById(runId).orElseThrow()
        val query =
            dsl.selectFrom(LINK_CRAWL_FAILED_JOBS)
                .where(LINK_CRAWL_FAILED_JOBS.RUN_ID.eq(runId).and(LINK_CRAWL_FAILED_JOBS.RESOLVED_AT_UTC.isNull))
                .orderBy(LINK_CRAWL_FAILED_JOBS.CREATED_AT_UTC.asc())

        val records =
            pageable?.let { query.limit(it.pageSize).offset(it.offset).fetch() }
                ?: query.fetch()
        return records.map { it.toLinkCrawlFailedJob(run) }
    }

    private fun LinkCrawlFailedJobsRecord.toLinkCrawlFailedJob(
        run: LinkCrawlRun = linkCrawlRunRepository.findById(requireNotNull(runId)).orElseThrow(),
    ): LinkCrawlFailedJob =
        LinkCrawlFailedJob(
            run = run,
            articleUrl = requireNotNull(articleUrl),
            errorStatusCode = requireNotNull(errorStatusCode),
            errorMessage = requireNotNull(errorMessage),
            failureCount = requireNotNull(failureCount),
            resolvedAt = resolvedAtUtc?.toInstant(),
            lastFailedAt = requireNotNull(lastFailedAtUtc).toInstant(),
        ).apply {
            id = requireNotNull(this@toLinkCrawlFailedJob.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }
}
