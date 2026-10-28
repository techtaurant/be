package com.techtaurant.mainserver.sitemap

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

@ConfigurationProperties("sitemap")
data class SitemapProperties(
    val enabled: Boolean = false,
    val bucketName: String = "",
    val siteUrl: String = "https://techtaurant.com",
) {
    init {
        if (enabled) {
            require(bucketName.isNotBlank()) { "SITEMAP_BUCKET_NAME is required when sitemap is enabled" }
            val uri = URI(siteUrl)
            require(
                uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                    uri.query == null && uri.fragment == null && uri.path.orEmpty() in listOf("", "/"),
            ) { "SITEMAP_SITE_URL must be an HTTPS origin" }
            require(siteUrl.length <= 255) { "SITEMAP_SITE_URL is too long" }
        }
    }
}
