package com.techtaurant.mainserver.security.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "security.refresh-token-whitelist")
data class RefreshTokenWhitelistPolicy(
    val maxActiveTokensPerUser: Int,
) {
    init {
        require(maxActiveTokensPerUser >= 1) {
            "maxActiveTokensPerUser must be at least 1"
        }
    }
}
