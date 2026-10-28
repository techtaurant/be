package com.techtaurant.mainserver.sitemap

import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import javax.xml.stream.XMLOutputFactory

@Component
class SitemapXmlWriter {
    fun write(posts: List<SitemapPost>): ByteArray {
        require(posts.size <= MAX_URLS) { "Sitemap exceeds 50,000 URLs; split before publishing" }
        require(posts.map { it.id }.toSet().size == posts.size) { "Duplicate sitemap URLs" }
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
                xml.flush()
                require(output.size() <= MAX_BYTES) { "Sitemap exceeds 50 MiB; split before publishing" }
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
