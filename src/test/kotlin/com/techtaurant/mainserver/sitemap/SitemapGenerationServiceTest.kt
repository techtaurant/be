package com.techtaurant.mainserver.sitemap

import com.techtaurant.mainserver.common.exception.ApiException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.core.SimpleLock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import java.util.UUID

class SitemapGenerationServiceTest {
    private val posts = mockk<SitemapPostRepository>()
    private val storage = mockk<SitemapStorage>(relaxed = true)
    private val provider = mockk<LockProvider>()
    private val lock = mockk<SimpleLock>(relaxed = true)
    private val meters = SimpleMeterRegistry()
    private val service =
        SitemapGenerationService(
            posts,
            SitemapXmlWriter(),
            storage,
            provider,
            meters,
        )

    init {
        every { provider.lock(any()) } returns Optional.of(lock)
        every { storage.current() } returns null
    }

    @Test
    fun `validation failure keeps previous object and releases the lease`() {
        val duplicate = SitemapPost(UUID.randomUUID(), Instant.EPOCH)
        every { posts.findPublished() } returns listOf(duplicate, duplicate)
        assertThatThrownBy { service.generate() }.isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 0) { storage.publish(any(), any()) }
        verify(exactly = 1) { lock.unlock() }
        assertThat(meters.get("sitemap.generation.failures").counter().count()).isEqualTo(1.0)
    }

    @Test
    fun `occupied lease prevents both database reads and publication`() {
        every { provider.lock(any()) } returns Optional.empty()
        assertThatThrownBy { service.generate() }.isInstanceOf(ApiException::class.java)
        verify(exactly = 0) { posts.findPublished() }
        verify(exactly = 0) { storage.publish(any(), any()) }
        verify(exactly = 0) { lock.unlock() }
    }

    @Test
    fun `successful run publishes once and records size and timestamp`() {
        every { posts.findPublished() } returns listOf(SitemapPost(UUID.randomUUID(), Instant.EPOCH))
        val result = service.generate()
        assertThat(result.urlCount).isEqualTo(1)
        assertThat(result.sizeBytes).isPositive()
        verify(exactly = 1) { storage.publish(any(), null) }
        verify(exactly = 1) { lock.unlock() }
        assertThat(
            meters.get("sitemap.last.published.timestamp").gauge().value(),
        ).isBetween(Instant.now().minusSeconds(5).epochSecond.toDouble(), Instant.now().epochSecond.toDouble())
    }

    @Test
    fun `publication failure retains last known timestamp and releases lease`() {
        every { storage.current() } returns PublishedSitemap("old", Instant.ofEpochSecond(100))
        service.refreshPublishedTimestamp()
        every { posts.findPublished() } returns emptyList()
        every { storage.publish(any(), any()) } throws IllegalStateException("S3 unavailable")
        assertThatThrownBy { service.generate() }.isInstanceOf(IllegalStateException::class.java)
        assertThat(meters.get("sitemap.last.published.timestamp").gauge().value()).isEqualTo(100.0)
        verify(exactly = 1) { lock.unlock() }
    }

    @Test
    fun `freshness recovers from S3 after restart and resets on deletion`() {
        every { storage.current() } returns PublishedSitemap("old", Instant.ofEpochSecond(12345))
        service.refreshPublishedTimestamp()
        assertThat(meters.get("sitemap.last.published.timestamp").gauge().value()).isEqualTo(12345.0)
        every { storage.current() } returns null
        service.refreshPublishedTimestamp()
        assertThat(meters.get("sitemap.last.published.timestamp").gauge().value()).isZero()
    }
}
