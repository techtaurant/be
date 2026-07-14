package com.techtaurant.mainserver.user.infrastructure.out

import com.techtaurant.mainserver.user.entity.UserToken
import com.techtaurant.mainserver.user.enums.UserRole
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserTokenRepository : JpaRepository<UserToken, UUID>, UserTokenRepositoryCustom {
    override fun existsByUserIdAndTokenHashAndUserRole(
        userId: UUID,
        tokenHash: String,
        role: UserRole,
    ): Boolean

    fun deleteAllByUserId(userId: UUID): Long
}
