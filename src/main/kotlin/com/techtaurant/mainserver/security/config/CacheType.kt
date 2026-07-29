package com.techtaurant.mainserver.security.config

import com.techtaurant.mainserver.security.jwt.JwtConstants

/**
 * 캐시 타입 정의
 *
 * ACCESS_TOKEN 캐싱은 제거되었으며, RefreshToken만 userId 기반으로 캐싱합니다.
 */
enum class CacheType(
    val cacheName: String,
) {
    REFRESH_TOKEN(JwtConstants.REFRESH_TOKEN_COOKIE),
}
