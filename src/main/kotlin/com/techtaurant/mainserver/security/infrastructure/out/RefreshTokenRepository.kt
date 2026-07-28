package com.techtaurant.mainserver.security.infrastructure.out

import java.util.UUID

interface RefreshTokenRepository {
    fun lockUser(userId: UUID): Boolean

    fun countByUserId(userId: UUID): Int

    fun deleteOldestByUserId(
        userId: UUID,
        limit: Int,
    ): Int

    fun insert(
        userId: UUID,
        tokenHash: String,
    )

    fun rotate(
        userId: UUID,
        expectedHash: String,
        replacementHash: String,
    ): Boolean

    fun deleteAllByUserId(userId: UUID): Int
}
