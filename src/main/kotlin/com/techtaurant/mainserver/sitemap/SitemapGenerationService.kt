package com.techtaurant.mainserver.sitemap

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.common.lock.ShedLock
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class SitemapGenerationResult(val urlCount: Int, val sizeBytes: Int, val objectKeys: List<String>)

/**
 * 공개 게시글 조회, XML 생성, S3 게시를 순서대로 실행합니다.
 * 정기 실행과 관리자 재실행이 같은 DB 잠금을 사용하며, 게시 결과를 운영 지표에 기록합니다.
 */
@Service
class SitemapGenerationService(
    private val posts: SitemapPostRepository,
    private val writer: SitemapXmlWriter,
    private val storage: SitemapStorage,
    private val shedLock: ShedLock,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastPublished = AtomicLong(0)

    init {
        meters.gauge("sitemap.last.published.timestamp", lastPublished)
        meters.counter("sitemap.generation.failures")
    }

    /**
     * 실행 잠금을 획득한 뒤 게시글 XML을 먼저 업로드하고 인덱스를 마지막에 게시합니다.
     * 두 파일은 원자적으로 교체되지 않습니다. 인덱스 업로드가 실패하면 갱신 완료로 기록하지 않으며,
     * 이미 업로드된 게시글 파일은 유지하고 다음 실행에서 다시 게시합니다.
     *
     * S3 I/O를 포함하는 전체 과정을 DB 트랜잭션으로 감싸지 않습니다.
     * 게시 직전 경과 시간을 검사하며, 실패한 실행도 잠금 해제와 소요 시간 기록을 시도합니다.
     *
     * @return 게시한 URL 수, 두 XML의 합계 바이트 수, S3 객체 경로 목록
     * @throws ApiException 다른 실행이 잠금을 보유한 경우
     * @throws IllegalStateException 업로드 시작 전 생성 제한 시간을 초과한 경우
     */
    fun generate(): SitemapGenerationResult {
        val started = System.nanoTime()
        return shedLock.withLock(
            key = "sitemap-publication",
            lockAtMostFor = LOCK_MAX_DURATION,
            lockAtLeastFor = LOCK_MIN_DURATION,
        ) {
            try {
                val previousPosts = storage.current(SitemapStorage.POSTS_KEY)
                val previousIndex = storage.current(SitemapStorage.INDEX_KEY)
                val publishedPosts = posts.findPublished()
                val postsXml = writer.writePosts(publishedPosts)
                val indexXml = writer.writeIndex()
                check(System.nanoTime() - started < PRE_PUBLICATION_DEADLINE.toNanos()) { "Sitemap generation deadline exceeded" }
                storage.publish(SitemapStorage.POSTS_KEY, postsXml, previousPosts)
                storage.publish(SitemapStorage.INDEX_KEY, indexXml, previousIndex)
                lastPublished.set(Instant.now().epochSecond)
                val sizeBytes = postsXml.size + indexXml.size
                val objectKeys = listOf(SitemapStorage.POSTS_KEY, SitemapStorage.INDEX_KEY)
                log.info("Published sitemap: urls={}, bytes={}, keys={}", publishedPosts.size, sizeBytes, objectKeys)
                SitemapGenerationResult(publishedPosts.size, sizeBytes, objectKeys)
            } catch (exception: Exception) {
                meters.counter("sitemap.generation.failures").increment()
                log.error("Sitemap publication failed; inspect S3 freshness before retrying", exception)
                throw exception
            } finally {
                meters.timer("sitemap.generation.duration").record(System.nanoTime() - started, TimeUnit.NANOSECONDS)
            }
        }
    }

    /**
     * 두 S3 파일 중 더 오래된 수정 시각으로 최근 게시 시각 지표를 갱신합니다.
     * 일부 파일만 갱신되어도 오래된 파일의 상태를 유지하며, 어느 하나라도 없으면 0으로 설정합니다.
     */
    fun refreshPublishedTimestamp() {
        val publishedPosts = storage.current(SitemapStorage.POSTS_KEY)
        val publishedIndex = storage.current(SitemapStorage.INDEX_KEY)
        lastPublished.set(
            if (publishedPosts == null || publishedIndex == null) {
                0
            } else {
                minOf(publishedPosts.lastModified, publishedIndex.lastModified).epochSecond
            },
        )
    }

    private companion object {
        val LOCK_MAX_DURATION: Duration = Duration.ofMinutes(10)
        val LOCK_MIN_DURATION: Duration = Duration.ofMinutes(1)
        val PRE_PUBLICATION_DEADLINE: Duration = Duration.ofSeconds(90)
    }
}
