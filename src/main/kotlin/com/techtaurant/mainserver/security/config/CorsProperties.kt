package com.techtaurant.mainserver.security.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.web.cors.CorsConfiguration

@ConfigurationProperties(prefix = "cors")
data class CorsProperties(
    val allowedOriginPatterns: String = "",
) {
    val parsedAllowedOriginPatterns: List<String>
        get() =
            allowedOriginPatterns
                .split(",")
                .map { it.trim().trimEnd('/') }
                .filter { it.isNotEmpty() }

    fun createCorsConfiguration(): CorsConfiguration =
        CorsConfiguration().apply {
            allowedOriginPatterns = parsedAllowedOriginPatterns
        }
}
