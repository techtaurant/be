package com.techtaurant.mainserver.attachment.cli

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal class AttachmentObjectKeyNormalizer(
    private val bucketName: String,
    private val region: String,
) {
    fun normalize(rawValue: String): String? {
        if (rawValue.isBlank()) return null
        if (!rawValue.contains("://")) return rawValue

        val uri = runCatching { URI(rawValue) }.getOrNull() ?: return null
        val rawPath = uri.rawPath ?: return null
        return when (uri.scheme?.lowercase()) {
            "s3" -> {
                if (uri.host != bucketName) null else decodePath(rawPath)
            }

            "http", "https" -> normalizeHttpUrl(uri.host ?: return null, rawPath)
            else -> null
        }?.takeIf(String::isNotBlank)
    }

    fun databaseRepresentations(objectKey: String): Set<String> {
        val encodedKey = encodePath(objectKey)
        return buildSet {
            add(objectKey)
            add("s3://$bucketName/$objectKey")
            addHttpRepresentations(objectKey)
            if (encodedKey != objectKey) {
                add("s3://$bucketName/$encodedKey")
                addHttpRepresentations(encodedKey)
            }
        }
    }

    private fun MutableSet<String>.addHttpRepresentations(objectKey: String) {
        listOf("http", "https").forEach { scheme ->
            add("$scheme://$bucketName.s3.$region.amazonaws.com/$objectKey")
            add("$scheme://$bucketName.s3.amazonaws.com/$objectKey")
            add("$scheme://s3.$region.amazonaws.com/$bucketName/$objectKey")
            add("$scheme://s3.amazonaws.com/$bucketName/$objectKey")
        }
    }

    private fun normalizeHttpUrl(
        host: String,
        rawPath: String,
    ): String? {
        val virtualHostedNames =
            setOf(
                "$bucketName.s3.$region.amazonaws.com",
                "$bucketName.s3.amazonaws.com",
            )
        if (host in virtualHostedNames) return decodePath(rawPath)

        val pathStyleNames = setOf("s3.$region.amazonaws.com", "s3.amazonaws.com")
        if (host !in pathStyleNames) return null
        val bucketPrefix = "/$bucketName/"
        if (!rawPath.startsWith(bucketPrefix)) return null
        return decodePath(rawPath.removePrefix("/$bucketName"))
    }

    private fun decodePath(rawPath: String): String =
        rawPath.removePrefix("/").split('/').joinToString("/") { segment ->
            URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8)
        }

    private fun encodePath(objectKey: String): String =
        objectKey.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")
        }
}
