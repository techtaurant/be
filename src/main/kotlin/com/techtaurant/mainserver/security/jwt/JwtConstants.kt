package com.techtaurant.mainserver.security.jwt

object JwtConstants {
    const val ACCESS_TOKEN_COOKIE = "accessToken"
    const val REFRESH_TOKEN_COOKIE = "refreshToken"

    const val TOKEN_TYPE_CLAIM = "typ"
    const val ACCESS_TOKEN_TYPE = "access"
    const val REFRESH_TOKEN_TYPE = "refresh"

    const val ROLE_CLAIM = "role"
    const val PERMANENT_CLAIM = "permanent"
    const val EXPIRING_ACCESS_TOKEN_IS_PERMANENT = false
    const val PERMANENT_ACCESS_TOKEN_IS_PERMANENT = true

    const val TOKEN_HASH_ALGORITHM = "SHA-256"
}
