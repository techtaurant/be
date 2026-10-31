package com.techtaurant.mainserver.sitemap.application

import com.techtaurant.mainserver.post.dto.PostForSitemap
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import javax.xml.stream.XMLOutputFactory

/**
 * 사이트맵 XML을 직렬화합니다.
 */
@Component
class SitemapXmlWriter(
    @Value("\${app.client-base-url}")
    private val clientBaseUrl: String,
) {
    /**
     * 전달받은 사이트맵 파일 경로를 가리키는 인덱스를 생성합니다.
     * FE rewrite를 통해 접근할 공개 주소를 사용하며, 선택 항목인 인덱스 lastmod는 생략합니다.
     */
    fun writeIndexXml(sitemapPaths: List<String>): ByteArray =
        buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">")
            sitemapPaths.forEach { path ->
                appendLine("    <sitemap><loc>$clientBaseUrl/$path</loc></sitemap>")
            }
            appendLine("</sitemapindex>")
        }.toByteArray(Charsets.UTF_8)

    /** 목록의 대표 URL만 포함합니다. 실제 갱신 시각을 추적하지 않으므로 lastmod는 생략합니다. */
    fun writeHomeXml(): ByteArray =
        buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">")
            appendLine("    <url><loc>$clientBaseUrl/posts</loc></url>")
            appendLine("</urlset>")
        }.toByteArray(Charsets.UTF_8)

    /** 조회한 게시글 한 페이지를 XML 바이트로 변환합니다. 파일 분할은 호출자가 담당합니다. */
    fun writePostsXml(posts: List<PostForSitemap>): ByteArray {
        val output = ByteArrayOutputStream()
        val xml = XMLOutputFactory.newFactory().createXMLStreamWriter(output, "UTF-8")
        try {
            xml.writeStartDocument("UTF-8", "1.0")
            xml.writeStartElement("urlset")
            xml.writeDefaultNamespace("http://www.sitemaps.org/schemas/sitemap/0.9")
            for (post in posts) {
                xml.writeStartElement("url")
                xml.writeStartElement("loc")
                xml.writeCharacters("$clientBaseUrl/posts/${post.id}")
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
        require(output.size() <= MAX_BYTES) { "Sitemap exceeds 50 MiB; split before publishing" }
        return output.toByteArray()
    }

    companion object {
        const val MAX_URLS = 50_000
        const val MAX_BYTES = 50 * 1024 * 1024
    }
}
