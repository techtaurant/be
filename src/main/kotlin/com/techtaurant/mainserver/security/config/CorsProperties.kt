package com.techtaurant.mainserver.security.config

import com.google.common.net.InternetDomainName
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.web.cors.CorsConfiguration
import java.net.URI

@ConfigurationProperties(prefix = "cors")
data class CorsProperties(
    val allowedOriginPatterns: String = "",
) {
    init {
        val invalidOriginPatterns = parsedAllowedOriginPatterns.filterNot(::isValidOriginPattern)
        require(invalidOriginPatterns.isEmpty()) {
            "허용되지 않는 CORS origin 패턴입니다: $invalidOriginPatterns"
        }
    }

    val parsedAllowedOriginPatterns: List<String>
        get() =
            allowedOriginPatterns
                .split(",")
                .map { it.trim().trimEnd('/') }
                .filter { it.isNotEmpty() }

    private fun isValidOriginPattern(pattern: String): Boolean {
        val schemeSeparatorIndex = pattern.indexOf(SCHEME_SEPARATOR)
        if (schemeSeparatorIndex <= 0) {
            return false
        }

        val scheme = pattern.substring(0, schemeSeparatorIndex)
        if (scheme != HTTP_SCHEME && scheme != HTTPS_SCHEME) {
            return false
        }

        val authority = pattern.substring(schemeSeparatorIndex + SCHEME_SEPARATOR.length)
        if (authority.isEmpty() || authority.any { it in ORIGIN_SUFFIX_OR_USER_INFO_CHARS }) {
            return false
        }

        val hostAndPort = parseHostAndPort(authority) ?: return false
        if (!isValidPortPattern(hostAndPort.portPattern)) {
            return false
        }

        val host = hostAndPort.host
        if (host.startsWith(IPV6_OPEN_BRACKET)) {
            return WILDCARD_CHAR !in host && isValidIpv6Literal(scheme, host, hostAndPort.portPattern)
        }

        if (WILDCARD_CHAR in host) {
            return host.count { it == WILDCARD_CHAR } == 1 &&
                host.startsWith(DNS_WILDCARD_PREFIX) &&
                isValidWildcardDnsSuffix(host.removePrefix(DNS_WILDCARD_PREFIX))
        }

        return isValidDnsHost(host)
    }

    private fun parseHostAndPort(authority: String): HostAndPort? {
        if (authority.startsWith(IPV6_OPEN_BRACKET)) {
            val closingBracketIndex = authority.indexOf(IPV6_CLOSE_BRACKET)
            if (closingBracketIndex < 0) {
                return null
            }

            val host = authority.substring(0, closingBracketIndex + 1)
            val portSuffix = authority.substring(closingBracketIndex + 1)
            val portPattern =
                when {
                    portSuffix.isEmpty() -> null
                    portSuffix.startsWith(PORT_SEPARATOR) -> portSuffix.drop(1)
                    else -> return null
                }
            return HostAndPort(host, portPattern)
        }

        val portSeparatorIndex = authority.lastIndexOf(PORT_SEPARATOR)
        return if (portSeparatorIndex < 0) {
            HostAndPort(authority, null)
        } else {
            HostAndPort(
                host = authority.substring(0, portSeparatorIndex),
                portPattern = authority.substring(portSeparatorIndex + 1),
            )
        }
    }

    private fun isValidPortPattern(portPattern: String?): Boolean =
        portPattern == null ||
            portPattern == ANY_PORT_PATTERN ||
            portPattern.toIntOrNull()?.let { it in MIN_PORT..MAX_PORT } == true

    private fun isValidDnsHost(host: String): Boolean =
        host.length in 1..MAX_DNS_HOST_LENGTH &&
            host.split(DOMAIN_SEPARATOR_CHAR).all(::isValidDnsLabel)

    private fun isValidWildcardDnsSuffix(host: String): Boolean =
        isValidDnsHost(host) &&
            runCatching { InternetDomainName.from(host).isUnderPublicSuffix }
                .getOrDefault(false)

    private fun isValidDnsLabel(label: String): Boolean =
        label.length in 1..MAX_DNS_LABEL_LENGTH &&
            label.first().isAsciiLetterOrDigit() &&
            label.last().isAsciiLetterOrDigit() &&
            label.all { it.isAsciiLetterOrDigit() || it == DNS_LABEL_SEPARATOR_CHAR }

    private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun isValidIpv6Literal(
        scheme: String,
        host: String,
        portPattern: String?,
    ): Boolean =
        runCatching {
            val concretePort = portPattern?.takeUnless { it == ANY_PORT_PATTERN }?.let { ":$it" }.orEmpty()
            URI("$scheme://$host$concretePort").host == host
        }.getOrDefault(false)

    fun createCorsConfiguration(): CorsConfiguration =
        CorsConfiguration().apply {
            allowedOriginPatterns = parsedAllowedOriginPatterns
        }

    companion object {
        private const val WILDCARD_CHAR = '*'
        private const val SCHEME_SEPARATOR = "://"
        private const val HTTP_SCHEME = "http"
        private const val HTTPS_SCHEME = "https"
        private const val DNS_WILDCARD_PREFIX = "*."
        private const val IPV6_OPEN_BRACKET = "["
        private const val IPV6_CLOSE_BRACKET = ']'
        private const val PORT_SEPARATOR = ':'
        private const val ANY_PORT_PATTERN = "[*]"
        private const val DNS_LABEL_SEPARATOR_CHAR = '-'
        private const val DOMAIN_SEPARATOR_CHAR = '.'
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65_535
        private const val MAX_DNS_LABEL_LENGTH = 63
        private const val MAX_DNS_HOST_LENGTH = 253
        private val ORIGIN_SUFFIX_OR_USER_INFO_CHARS = setOf('/', '?', '#', '@')
    }

    private data class HostAndPort(
        val host: String,
        val portPattern: String?,
    )
}
