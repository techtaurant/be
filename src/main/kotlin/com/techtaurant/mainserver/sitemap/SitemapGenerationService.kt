package com.techtaurant.mainserver.sitemap

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.common.lock.LockStatus
import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class SitemapGenerationResult(val urlCount: Int, val sizeBytes: Int, val objectKey: String)

/**
 * 공개 게시글 조회, XML 생성, S3 게시를 순서대로 실행합니다.
 * 정기 실행과 관리자 재실행이 같은 DB 잠금을 사용하며, 게시 결과를 운영 지표에 기록합니다.
 */
@Service
@ConditionalOnProperty("app.environment", havingValue = "prod")
class SitemapGenerationService(
    private val posts: SitemapPostRepository,
    private val writer: SitemapXmlWriter,
    private val storage: SitemapStorage,
    @Qualifier("sitemapLockProvider") private val lockProvider: LockProvider,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastPublished = AtomicLong(0)

    init {
        meters.gauge("sitemap.last.published.timestamp", lastPublished)
        meters.counter("sitemap.generation.failures")
    }

    /**
     * 실행 잠금을 획득한 뒤 XML을 완성하고 검증된 결과만 S3에 게시합니다.
     *
     * S3 I/O를 포함하는 전체 과정을 DB 트랜잭션으로 감싸지 않습니다.
     * 게시 직전 경과 시간을 검사하며, 실패한 실행도 잠금 해제와 소요 시간 기록을 시도합니다.
     *
     * @return 게시한 URL 수, XML 바이트 수, S3 객체 경로
     * @throws ApiException 다른 실행이 잠금을 보유한 경우
     * @throws IllegalStateException 업로드 시작 전 생성 제한 시간을 초과한 경우
     */
    fun generate(): SitemapGenerationResult {
        val started = System.nanoTime()
        val lock =
            lockProvider.lock(
                LockConfiguration(Instant.now(), "sitemap-publication", LOCK_MAX_DURATION, LOCK_MIN_DURATION),
            ).orElseThrow { ApiException(LockStatus.LOCK_ACQUISITION_FAILED) }
        try {
            val previous = storage.current()
            val publishedPosts = posts.findPublished()
            val xml = writer.write(publishedPosts)
            check(System.nanoTime() - started < PRE_PUBLICATION_DEADLINE.toNanos()) { "Sitemap generation deadline exceeded" }
            storage.publish(xml, previous)
            lastPublished.set(Instant.now().epochSecond)
            log.info("Published sitemap: urls={}, bytes={}, key={}", publishedPosts.size, xml.size, SitemapStorage.KEY)
            return SitemapGenerationResult(publishedPosts.size, xml.size, SitemapStorage.KEY)
        } catch (exception: Exception) {
            meters.counter("sitemap.generation.failures").increment()
            log.error("Sitemap publication failed; inspect S3 freshness before retrying", exception)
            throw exception
        } finally {
            try {
                lock.unlock()
            } finally {
                meters.timer("sitemap.generation.duration").record(System.nanoTime() - started, TimeUnit.NANOSECONDS)
            }
        }
    }

    /**
     * S3 파일의 최종 수정 시각으로 최근 게시 시각 지표를 갱신합니다.
     * 재시작이나 다른 서버의 게시 결과도 반영하며, 파일이 없으면 지표를 0으로 설정합니다.
     */
    fun refreshPublishedTimestamp() {
        lastPublished.set(storage.current()?.lastModified?.epochSecond ?: 0)
    }

    private companion object {
        val LOCK_MAX_DURATION: Duration = Duration.ofMinutes(10)
        val LOCK_MIN_DURATION: Duration = Duration.ofMinutes(1)
        val PRE_PUBLICATION_DEADLINE: Duration = Duration.ofSeconds(90)
    }
}
