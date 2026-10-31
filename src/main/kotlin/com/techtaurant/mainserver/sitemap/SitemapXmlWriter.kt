package com.techtaurant.mainserver.sitemap

import com.techtaurant.mainserver.post.dto.PostForSitemap
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.stream.XMLOutputFactory

/**
 * 사이트맵 XML을 직렬화합니다.
 */
@Component
class SitemapXmlWriter {
    /**
     * 홈 및 게시글 사이트맵을 가리키는 인덱스를 생성합니다.
     * FE rewrite를 통해 접근할 공개 주소를 사용하며, 선택 항목인 인덱스 lastmod는 생략합니다.
     */
    fun writeIndexXml(fileCount: Int): ByteArray =
        buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">")
            appendLine("    <sitemap><loc>$CLIENT_BASE_URL/sitemap/home.xml</loc></sitemap>")
            for (number in 1..fileCount) {
                appendLine("    <sitemap><loc>$CLIENT_BASE_URL/sitemap/posts-$number.xml</loc></sitemap>")
            }
            appendLine("</sitemapindex>")
        }.toByteArray(Charsets.UTF_8)

    /** 목록의 대표 URL만 포함합니다. 실제 갱신 시각을 추적하지 않으므로 lastmod는 생략합니다. */
    fun writeHomeXml(): ByteArray =
        buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">")
            appendLine("    <url><loc>$CLIENT_BASE_URL/posts</loc></url>")
            appendLine("</urlset>")
        }.toByteArray(Charsets.UTF_8)

    /**
     * 청크에서 전달된 게시글을 순차 소비하여 임시 파일에 기록합니다.
     * 최대 50,000개까지 기록하며, 남은 게시글은 같은 Iterator로 다음 파일에 이어 씁니다.
     *
     * @return XML에 기록한 URL 수
     */
    fun writePostsXml(
        posts: Iterator<PostForSitemap>,
        file: Path,
    ): Int {
        var count = 0
        Files.newOutputStream(file).buffered().use { output ->
            val xml = XMLOutputFactory.newFactory().createXMLStreamWriter(output, "UTF-8")
            try {
                xml.writeStartDocument("UTF-8", "1.0")
                xml.writeStartElement("urlset")
                xml.writeDefaultNamespace("http://www.sitemaps.org/schemas/sitemap/0.9")
                while (count < MAX_URLS && posts.hasNext()) {
                    val post = posts.next()
                    count++
                    xml.writeStartElement("url")
                    xml.writeStartElement("loc")
                    xml.writeCharacters("$CLIENT_BASE_URL/posts/${post.id}")
                    xml.writeEndElement()
                    xml.writeStartElement("lastmod")
                    xml.writeCharacters(post.updatedAt.toString())
                    xml.writeEndElement()
                    xml.writeEndElement()
                }
                xml.writeEndElement()
                xml.writeEndDocument()
                xml.flush()
            } finally {
                xml.close()
            }
        }
        require(Files.size(file) <= MAX_BYTES) { "Sitemap exceeds 50 MiB; split before publishing" }
        return count
    }

    companion object {
        private const val CLIENT_BASE_URL = "https://techtaurant.com"
        const val MAX_URLS = 50_000
        const val MAX_BYTES = 50 * 1024 * 1024
    }
}
