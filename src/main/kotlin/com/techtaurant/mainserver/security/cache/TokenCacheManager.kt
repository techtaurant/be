package com.techtaurant.mainserver.security.cache

import com.techtaurant.mainserver.security.config.CacheType
import org.springframework.cache.CacheManager
import org.springframework.stereotype.Component

/**
 * 토큰 캐싱을 관리하는 매니저
 *
 * Spring CacheManager를 통해 캐시 추상화를 활용하여
 * 구현체(Caffeine 등)에 독립적인 캐시 작업을 제공합니다.
 */
@Component
class TokenCacheManager(
    private val cacheManager: CacheManager,
) : TokenCachePort {
    override fun <T> save(
        cacheType: CacheType,
        key: String,
        value: T,
    ) {
        val cache = cacheManager.getCache(cacheType.cacheName)
        cache?.put(key, value)
    }

    override fun <T> get(
        cacheType: CacheType,
        key: String,
        type: Class<T>,
    ): T? {
        val cache = cacheManager.getCache(cacheType.cacheName)
        return cache?.get(key, type)
    }

    override fun delete(
        cacheType: CacheType,
        key: String,
    ) {
        val cache = cacheManager.getCache(cacheType.cacheName)
        cache?.evict(key)
    }

    override fun saveRefreshToken(
        userId: String,
        token: String,
    ) {
        save(CacheType.REFRESH_TOKEN, userId, token)
    }

    override fun getRefreshToken(userId: String): String? {
        return get(CacheType.REFRESH_TOKEN, userId, String::class.java)
    }

    override fun deleteRefreshToken(userId: String) {
        delete(CacheType.REFRESH_TOKEN, userId)
    }
}
