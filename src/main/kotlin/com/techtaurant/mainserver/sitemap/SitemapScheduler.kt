package com.techtaurant.mainserver.sitemap

import com.techtaurant.mainserver.post.dto.PostForSitemap
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Component
class SitemapScheduler(
    private val postRepository: PostRepository,
    private val sitemapXmlWriter: SitemapXmlWriter,
    private val sitemapStorage: SitemapStorage,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 게시글 파일을 먼저 게시하고 인덱스를 마지막에 갱신합니다. 다른 서버가 실행 중이면 건너뜁니다. */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "sitemap-publication", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    fun generate() {
        val directory = Files.createTempDirectory("sitemap-")
        val files = mutableListOf<Path>()
        try {
            val posts = publishedPosts().iterator()
            var count = 0L
            do {
                val file = directory.resolve("posts-${files.size + 1}.xml")
                files.add(file)
                count += sitemapXmlWriter.writePostsXml(posts, file)
            } while (posts.hasNext())

            files.forEach { file ->
                sitemapStorage.publishFile("sitemap/${file.fileName}", file)
            }
            sitemapStorage.publish(SitemapStorage.HOME_KEY, sitemapXmlWriter.writeHomeXml())
            sitemapStorage.publish(SitemapStorage.INDEX_KEY, sitemapXmlWriter.writeIndexXml(files.size))
            log.info("Published sitemap: urls={}, files={}", count, files.size)
        } finally {
            files.forEach { Files.deleteIfExists(it) }
            Files.deleteIfExists(directory)
        }
    }

    /** 마지막 ID를 다음 조회의 커서로 사용하며, 이전 청크를 누적하지 않습니다. */
    private fun publishedPosts(): Sequence<PostForSitemap> =
        sequence {
            var afterId: UUID? = null
            do {
                val chunk = postRepository.findPostsForSitemap(afterId, 1_000)
                if (chunk.isEmpty()) {
                    break
                }
                yieldAll(chunk)
                afterId = chunk.last().id
            } while (chunk.size == 1_000)
        }
}
