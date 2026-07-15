package com.techtaurant.mainserver.user.infrastructure.out

import com.techtaurant.mainserver.user.entity.UserToken
import com.techtaurant.mainserver.user.enums.UserRole
import org.springframework.data.repository.Repository
import java.util.UUID

interface UserTokenRepository : Repository<UserToken, UUID>, UserTokenRepositoryCustom {
    override fun existsByUserIdAndTokenHashAndUserRole(
        userId: UUID,
        tokenHash: String,
        role: UserRole,
    ): Boolean

    override fun deleteAllByUserId(userId: UUID): Long
}
