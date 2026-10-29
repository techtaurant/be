package com.techtaurant.mainserver.sitemap

import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import javax.xml.stream.XMLOutputFactory

/**
 * 공개 게시글 목록으로 단일 사이트맵 XML 파일을 생성합니다.
 * DB 조회나 S3 업로드 없이 XML 직렬화와 파일 한도 검사만 수행합니다.
 */
@Component
class SitemapXmlWriter {
    /**
     * FE 게시글 주소와 실제 수정 시각(lastmod)을 UTF-8 XML로 직렬화합니다.
     *
     * 전체 목록과 완성된 XML을 메모리에 보관합니다. 파일 분할은 지원하지 않으며,
     * 한도를 초과하면 일부 URL만 게시하지 않고 예외를 발생시킵니다.
     *
     * @param posts 사이트맵에 포함할 공개 게시글 목록
     * @return S3에 업로드할 완성된 XML 바이트
     * @throws IllegalArgumentException URL이 50,000개 또는 XML이 50 MiB를 초과한 경우
     */
    fun write(posts: List<SitemapPost>): ByteArray {
        require(posts.size <= MAX_URLS) { "Sitemap exceeds 50,000 URLs; split before publishing" }
        val output = ByteArrayOutputStream()
        val xml = XMLOutputFactory.newFactory().createXMLStreamWriter(output, "UTF-8")
        try {
            xml.writeStartDocument("UTF-8", "1.0")
            xml.writeStartElement("urlset")
            xml.writeDefaultNamespace("http://www.sitemaps.org/schemas/sitemap/0.9")
            posts.forEach { post ->
                xml.writeStartElement("url")
                xml.writeStartElement("loc")
                xml.writeCharacters("https://techtaurant.com/posts/${post.id}")
                xml.writeEndElement()
                xml.writeStartElement("lastmod")
                xml.writeCharacters(post.updatedAt.toString())
                xml.writeEndElement()
                xml.writeEndElement()
            }
            xml.writeEndElement()
            xml.writeEndDocument()
            xml.flush()
            require(output.size() <= MAX_BYTES) { "Sitemap exceeds 50 MiB; split before publishing" }
            return output.toByteArray()
        } finally {
            xml.close()
        }
    }

    companion object {
        const val MAX_URLS = 50_000
        const val MAX_BYTES = 50 * 1024 * 1024
    }
}
