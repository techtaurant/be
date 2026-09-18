package com.techtaurant.mainserver.link.infrastructure.out

import com.github.f4b6a3.uuid.UuidCreator
import com.techtaurant.mainserver.jooq.tables.LinkCrawlBatches.Companion.LINK_CRAWL_BATCHES
import com.techtaurant.mainserver.jooq.tables.LinkCrawlFailedJobs.Companion.LINK_CRAWL_FAILED_JOBS
import com.techtaurant.mainserver.jooq.tables.records.LinkCrawlFailedJobsRecord
import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import com.techtaurant.mainserver.link.entity.LinkCrawlFailedJob
import com.techtaurant.mainserver.link.entity.LinkCrawlRun
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
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
    private val linkCrawlBatchRepository: LinkCrawlBatchRepository,
    private val linkCrawlRunRepository: LinkCrawlRunRepository,
) : LinkCrawlFailedJobRepository {
    override fun findById(id: UUID): Optional<LinkCrawlFailedJob> =
        Optional.ofNullable(
            dsl.selectFrom(LINK_CRAWL_FAILED_JOBS).where(LINK_CRAWL_FAILED_JOBS.ID.eq(id)).fetchOne()?.toLinkCrawlFailedJob(),
        )

    override fun findAllByLastRunIdAndResolvedAtIsNullOrderByCreatedAtAsc(runId: UUID): List<LinkCrawlFailedJob> = unresolvedJobs(runId)

    override fun findAllByLastRunIdAndResolvedAtIsNullOrderByCreatedAtAsc(
        runId: UUID,
        pageable: Pageable,
    ): List<LinkCrawlFailedJob> = unresolvedJobs(runId, pageable)

    override fun countByLastRunIdAndResolvedAtIsNull(runId: UUID): Long =
        dsl.fetchCount(
            LINK_CRAWL_FAILED_JOBS,
            LINK_CRAWL_FAILED_JOBS.LAST_RUN_ID.eq(runId).and(LINK_CRAWL_FAILED_JOBS.RESOLVED_AT_UTC.isNull),
        ).toLong()

    override fun findRetryableAutomaticJobs(
        maxFailureCount: Int,
        retryableBefore: Instant,
        pageable: Pageable,
    ): List<LinkCrawlFailedJob> =
        dsl.select(LINK_CRAWL_FAILED_JOBS.fields().toList())
            .from(LINK_CRAWL_FAILED_JOBS)
            .join(LINK_CRAWL_BATCHES).on(LINK_CRAWL_FAILED_JOBS.BATCH_ID.eq(LINK_CRAWL_BATCHES.ID))
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

    override fun findAllByBatchIdFilteredByResolution(
        batchId: UUID,
        resolved: Boolean?,
    ): List<LinkCrawlFailedJob> {
        val batch = linkCrawlBatchRepository.findById(batchId).orElseThrow()
        val records =
            dsl.selectFrom(LINK_CRAWL_FAILED_JOBS)
                .where(LINK_CRAWL_FAILED_JOBS.BATCH_ID.eq(batchId).and(resolvedCondition(resolved)))
                .orderBy(LINK_CRAWL_FAILED_JOBS.CREATED_AT_UTC.asc())
                .fetch()

        val runsById = findRunsById(records.mapNotNull { it.lastRunId }.toSet())
        return records.map { it.toLinkCrawlFailedJob(batch, runsById[it.lastRunId]) }
    }

    override fun findByBatchIdAndArticleUrl(
        batchId: UUID,
        articleUrl: String,
    ): LinkCrawlFailedJob? =
        dsl.selectFrom(LINK_CRAWL_FAILED_JOBS)
            .where(LINK_CRAWL_FAILED_JOBS.BATCH_ID.eq(batchId).and(LINK_CRAWL_FAILED_JOBS.ARTICLE_URL.eq(articleUrl)))
            .fetchOne()
            ?.toLinkCrawlFailedJob()

    override fun findRunIdsWithUnresolvedJobs(runIds: Collection<UUID>): Set<UUID> =
        if (runIds.isEmpty()) {
            emptySet()
        } else {
            dsl.selectDistinct(LINK_CRAWL_FAILED_JOBS.LAST_RUN_ID)
                .from(LINK_CRAWL_FAILED_JOBS)
                .where(LINK_CRAWL_FAILED_JOBS.LAST_RUN_ID.`in`(runIds).and(LINK_CRAWL_FAILED_JOBS.RESOLVED_AT_UTC.isNull))
                .fetchSet(LINK_CRAWL_FAILED_JOBS.LAST_RUN_ID)
                .filterNotNull()
                .toSet()
        }

    /**
     * 같은 배치의 같은 URL은 행 하나로 누적되므로, 읽고 나서 쓰지 않고 (batch_id, article_url) 충돌을 DB가 처리하게 한다.
     * 겹친 두 실행이 같은 URL을 처음 실패로 기록해도 유일성 위반으로 실행 전체가 롤백되지 않는다.
     */
    override fun recordFailure(
        batchId: UUID,
        articleUrl: String,
        lastRunId: UUID,
        errorStatusCode: Int,
        errorMessage: String,
        failedAt: Instant,
    ) {
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        val failedAtUtc = failedAt.atOffset(ZoneOffset.UTC)
        dsl.insertInto(LINK_CRAWL_FAILED_JOBS)
            .set(LINK_CRAWL_FAILED_JOBS.ID, UuidCreator.getTimeOrderedEpoch())
            .set(LINK_CRAWL_FAILED_JOBS.BATCH_ID, batchId)
            .set(LINK_CRAWL_FAILED_JOBS.LAST_RUN_ID, lastRunId)
            .set(LINK_CRAWL_FAILED_JOBS.ARTICLE_URL, articleUrl)
            .set(LINK_CRAWL_FAILED_JOBS.ERROR_STATUS_CODE, errorStatusCode)
            .set(LINK_CRAWL_FAILED_JOBS.ERROR_MESSAGE, errorMessage)
            .set(LINK_CRAWL_FAILED_JOBS.FAILURE_COUNT, 1)
            .set(LINK_CRAWL_FAILED_JOBS.LAST_FAILED_AT_UTC, failedAtUtc)
            .set(LINK_CRAWL_FAILED_JOBS.CREATED_AT_UTC, now)
            .set(LINK_CRAWL_FAILED_JOBS.UPDATED_AT_UTC, now)
            .onConflict(LINK_CRAWL_FAILED_JOBS.BATCH_ID, LINK_CRAWL_FAILED_JOBS.ARTICLE_URL)
            .doUpdate()
            // 해소됐던 URL이 다시 깨진 것은 이전 실패와 별개 사건이라 재시도 상한을 처음부터 다시 준다.
            .set(
                LINK_CRAWL_FAILED_JOBS.FAILURE_COUNT,
                DSL.`when`(LINK_CRAWL_FAILED_JOBS.RESOLVED_AT_UTC.isNull, LINK_CRAWL_FAILED_JOBS.FAILURE_COUNT.plus(1)).otherwise(1),
            )
            .set(LINK_CRAWL_FAILED_JOBS.RESOLVED_AT_UTC, DSL.castNull(LINK_CRAWL_FAILED_JOBS.RESOLVED_AT_UTC))
            .set(LINK_CRAWL_FAILED_JOBS.LAST_RUN_ID, lastRunId)
            .set(LINK_CRAWL_FAILED_JOBS.ERROR_STATUS_CODE, errorStatusCode)
            .set(LINK_CRAWL_FAILED_JOBS.ERROR_MESSAGE, errorMessage)
            .set(LINK_CRAWL_FAILED_JOBS.LAST_FAILED_AT_UTC, failedAtUtc)
            .set(LINK_CRAWL_FAILED_JOBS.UPDATED_AT_UTC, now)
            .execute()
    }

    /**
     * 미해소일 때만 해소 시각을 쓰고, 이번 호출이 해소했는지를 돌려준다.
     * 조건을 UPDATE에 두어 같은 행을 동시에 해소하려는 요청 중 하나만 true를 받고, 이미 해소된 행을 다시 덮어쓰지 않는다.
     */
    override fun markResolvedIfUnresolved(
        batchId: UUID,
        articleUrl: String,
        resolvedAt: Instant,
    ): Boolean {
        val resolvedAtUtc = resolvedAt.atOffset(ZoneOffset.UTC)
        val updatedRowCount =
            dsl.update(LINK_CRAWL_FAILED_JOBS)
                .set(LINK_CRAWL_FAILED_JOBS.RESOLVED_AT_UTC, resolvedAtUtc)
                .set(LINK_CRAWL_FAILED_JOBS.UPDATED_AT_UTC, resolvedAtUtc)
                .where(
                    LINK_CRAWL_FAILED_JOBS.BATCH_ID.eq(batchId)
                        .and(LINK_CRAWL_FAILED_JOBS.ARTICLE_URL.eq(articleUrl))
                        .and(LINK_CRAWL_FAILED_JOBS.RESOLVED_AT_UTC.isNull),
                ).execute()
        return updatedRowCount > 0
    }

    /**
     * 재시도는 외부 fetch 동안 트랜잭션 밖에 있으므로, 그 사이 다른 경로가 해소한 행에 실패를 덮어쓰지 않도록 미해소일 때만 반영한다.
     */
    override fun recordRetryFailureIfUnresolved(
        failedJobId: UUID,
        errorStatusCode: Int,
        errorMessage: String,
        failedAt: Instant,
    ) {
        val failedAtUtc = failedAt.atOffset(ZoneOffset.UTC)
        dsl.update(LINK_CRAWL_FAILED_JOBS)
            .set(LINK_CRAWL_FAILED_JOBS.FAILURE_COUNT, LINK_CRAWL_FAILED_JOBS.FAILURE_COUNT.plus(1))
            .set(LINK_CRAWL_FAILED_JOBS.ERROR_STATUS_CODE, errorStatusCode)
            .set(LINK_CRAWL_FAILED_JOBS.ERROR_MESSAGE, errorMessage)
            .set(LINK_CRAWL_FAILED_JOBS.LAST_FAILED_AT_UTC, failedAtUtc)
            .set(LINK_CRAWL_FAILED_JOBS.UPDATED_AT_UTC, failedAtUtc)
            .where(LINK_CRAWL_FAILED_JOBS.ID.eq(failedJobId).and(LINK_CRAWL_FAILED_JOBS.RESOLVED_AT_UTC.isNull))
            .execute()
    }

    private fun resolvedCondition(resolved: Boolean?): Condition =
        when (resolved) {
            null -> DSL.noCondition()
            true -> LINK_CRAWL_FAILED_JOBS.RESOLVED_AT_UTC.isNotNull
            false -> LINK_CRAWL_FAILED_JOBS.RESOLVED_AT_UTC.isNull
        }

    private fun findRunsById(runIds: Set<UUID>): Map<UUID, LinkCrawlRun> =
        linkCrawlRunRepository.findAllByIdIn(runIds).associateBy { requireNotNull(it.id) }

    private fun unresolvedJobs(
        runId: UUID,
        pageable: Pageable? = null,
    ): List<LinkCrawlFailedJob> {
        val run = linkCrawlRunRepository.findById(runId).orElseThrow()
        val query =
            dsl.selectFrom(LINK_CRAWL_FAILED_JOBS)
                .where(LINK_CRAWL_FAILED_JOBS.LAST_RUN_ID.eq(runId).and(LINK_CRAWL_FAILED_JOBS.RESOLVED_AT_UTC.isNull))
                .orderBy(LINK_CRAWL_FAILED_JOBS.CREATED_AT_UTC.asc())

        val records =
            pageable?.let { query.limit(it.pageSize).offset(it.offset).fetch() }
                ?: query.fetch()
        return records.map { it.toLinkCrawlFailedJob(run.batch, run) }
    }

    private fun LinkCrawlFailedJobsRecord.toLinkCrawlFailedJob(
        batch: LinkCrawlBatch = linkCrawlBatchRepository.findById(requireNotNull(batchId)).orElseThrow(),
        lastRun: LinkCrawlRun? = lastRunId?.let { linkCrawlRunRepository.findById(it).orElse(null) },
    ): LinkCrawlFailedJob =
        LinkCrawlFailedJob(
            batch = batch,
            articleUrl = requireNotNull(articleUrl),
            errorStatusCode = requireNotNull(errorStatusCode),
            errorMessage = requireNotNull(errorMessage),
            failureCount = requireNotNull(failureCount),
            resolvedAt = resolvedAtUtc?.toInstant(),
            lastFailedAt = requireNotNull(lastFailedAtUtc).toInstant(),
            lastRun = lastRun,
        ).apply {
            id = requireNotNull(this@toLinkCrawlFailedJob.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }
}
