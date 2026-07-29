package com.techtaurant.mainserver.security.cache

import com.techtaurant.mainserver.security.config.CacheType

/**
 * 토큰 캐싱 추상화 인터페이스
 *
 * 캐시 구현체(Caffeine, Redis 등)에 독립적인 캐시 작업을 정의합니다.
 * 구현체를 교체하여 캐시 백엔드를 변경할 수 있습니다.
 */
interface TokenCachePort {
    /**
     * 캐시에 값을 저장합니다.
     *
     * @param cacheType 캐시 타입 (TTL 및 캐시 이름 결정)
     * @param key 캐시 키
     * @param value 저장할 값
     */
    fun <T> save(
        cacheType: CacheType,
        key: String,
        value: T,
    )

    /**
     * 캐시에서 값을 조회합니다.
     *
     * @param cacheType 캐시 타입
     * @param key 캐시 키
     * @param type 반환 타입 클래스
     * @return 캐시된 값 (없으면 null)
     */
    fun <T> get(
        cacheType: CacheType,
        key: String,
        type: Class<T>,
    ): T?

    /**
     * 캐시에서 값을 삭제합니다.
     *
     * @param cacheType 캐시 타입
     * @param key 캐시 키
     */
    fun delete(
        cacheType: CacheType,
        key: String,
    )

    /**
     * RefreshToken을 캐시에 저장합니다.
     *
     * @param userId 사용자 ID
     * @param token RefreshToken 값
     */
    fun saveRefreshToken(
        userId: String,
        token: String,
    )

    /**
     * RefreshToken을 캐시에서 조회합니다.
     *
     * @param userId 사용자 ID
     * @return RefreshToken (없으면 null)
     */
    fun getRefreshToken(userId: String): String?

    /**
     * RefreshToken을 캐시에서 삭제합니다.
     *
     * @param userId 사용자 ID
     */
    fun deleteRefreshToken(userId: String)
}
