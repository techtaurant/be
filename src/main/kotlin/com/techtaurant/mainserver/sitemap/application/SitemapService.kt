package com.techtaurant.mainserver.sitemap.application

import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.sitemap.infrastructure.out.SitemapStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class SitemapService(
    private val postRepository: PostRepository,
    private val sitemapXmlWriter: SitemapXmlWriter,
    private val sitemapStorage: SitemapStorage,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun generate() {
        val sitemapPaths = mutableListOf<String>()
        var totalUrls = 0L

        // 게시글 sitemap xml 생성
        var postFileCount = 0
        var postAfterId: UUID? = null
        do {
            val posts = postRepository.findPostsForSitemap(postAfterId, SitemapXmlWriter.MAX_URLS)
            if (posts.isEmpty()) {
                break
            }
            val path = "sitemap/posts-${++postFileCount}.xml"

            sitemapStorage.publishXml(
                path,
                sitemapXmlWriter.writePostsXml(posts),
            )

            sitemapPaths.add(path)
            totalUrls += posts.size
            postAfterId = posts.last().id
        } while (posts.size == SitemapXmlWriter.MAX_URLS)

        // 홈 화면 sitemap xml 생성
        val homePath = "sitemap/home.xml"
        sitemapStorage.publishXml(
            homePath,
            sitemapXmlWriter.writeHomeXml(),
        )
        sitemapPaths.add(homePath)

        // sitemap index xml 생성
        sitemapStorage.publishXml(
            "sitemap/index.xml",
            sitemapXmlWriter.writeIndexXml(sitemapPaths),
        )

        log.info("Published sitemap: urls={}, postFiles={}", totalUrls, postFileCount)
    }
}
