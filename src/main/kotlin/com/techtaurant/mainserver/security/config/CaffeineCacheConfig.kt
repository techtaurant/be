package com.techtaurant.mainserver.security.config

import com.github.benmanes.caffeine.cache.Caffeine
import com.techtaurant.mainserver.security.jwt.JwtProperties
import org.springframework.cache.CacheManager
import org.springframework.cache.caffeine.CaffeineCache
import org.springframework.cache.support.SimpleCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

/**
 * Caffeine 기반 캐시 설정
 *
 * CacheType별로 TTL이 다른 Caffeine 캐시를 생성합니다.
 * 캐시 백엔드를 변경하려면 이 Configuration을 교체하면 됩니다.
 */
@Configuration
class CaffeineCacheConfig(
    private val jwtProperties: JwtProperties,
) {
    @Bean
    fun cacheManager(): CacheManager {
        val cacheTtlMap =
            mapOf(
                CacheType.REFRESH_TOKEN to jwtProperties.refreshTokenExpireMs,
            )

        val caches =
            CacheType.entries.map { cacheType ->
                val ttlMs = cacheTtlMap[cacheType] ?: 3600000L
                CaffeineCache(
                    cacheType.cacheName,
                    Caffeine.newBuilder()
                        .expireAfterWrite(ttlMs, TimeUnit.MILLISECONDS)
                        .maximumSize(10000)
                        .build(),
                )
            }

        return SimpleCacheManager().apply {
            setCaches(caches)
        }
    }
}
