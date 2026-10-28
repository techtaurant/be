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

@Service
@ConditionalOnProperty("sitemap.enabled", havingValue = "true")
class SitemapGenerationService(
    private val posts: SitemapPostRepository,
    private val writer: SitemapXmlWriter,
    private val storage: SitemapStorage,
    private val properties: SitemapProperties,
    @Qualifier("sitemapLockProvider") private val lockProvider: LockProvider,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastPublished = AtomicLong(0)

    init {
        meters.gauge("sitemap.last.published.timestamp", lastPublished)
        meters.counter("sitemap.generation.failures")
    }

    // Manual and scheduled callers use this same lease. No encompassing DB transaction during S3 I/O.
    fun generate(): SitemapGenerationResult {
        val started = System.nanoTime()
        val lock =
            lockProvider.lock(
                LockConfiguration(Instant.now(), "sitemap-publication", Duration.ofMinutes(10), Duration.ofMinutes(1)),
            ).orElseThrow { ApiException(LockStatus.LOCK_ACQUISITION_FAILED) }
        try {
            val previous = storage.current()
            val publishedPosts = posts.findPublished()
            val xml = writer.write(publishedPosts, properties.siteUrl)
            check(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(90)) { "Sitemap generation deadline exceeded" }
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

    // S3 is the durable source of freshness, including after application restarts and other nodes' runs.
    fun refreshPublishedTimestamp() {
        lastPublished.set(storage.current()?.lastModified?.epochSecond ?: 0)
    }
}
