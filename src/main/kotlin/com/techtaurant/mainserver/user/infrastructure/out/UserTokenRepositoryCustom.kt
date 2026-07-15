package com.techtaurant.mainserver.user.infrastructure.out

import com.techtaurant.mainserver.user.entity.UserToken
import com.techtaurant.mainserver.user.enums.UserRole
import java.util.UUID

interface UserTokenRepositoryCustom {
    fun saveAndFlush(userToken: UserToken): UserToken

    fun deleteAllByUserId(userId: UUID): Long

    fun deleteAllInBatch()

    fun findAll(): List<UserToken>

    fun count(): Long

    fun flush()

    fun existsByUserIdAndTokenHashAndUserRole(
        userId: UUID,
        tokenHash: String,
        role: UserRole,
    ): Boolean
}
