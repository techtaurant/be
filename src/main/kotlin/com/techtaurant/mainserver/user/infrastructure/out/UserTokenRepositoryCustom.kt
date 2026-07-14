package com.techtaurant.mainserver.user.infrastructure.out

import com.techtaurant.mainserver.user.enums.UserRole
import java.util.UUID

interface UserTokenRepositoryCustom {
    fun existsByUserIdAndTokenHashAndUserRole(
        userId: UUID,
        tokenHash: String,
        role: UserRole,
    ): Boolean
}
