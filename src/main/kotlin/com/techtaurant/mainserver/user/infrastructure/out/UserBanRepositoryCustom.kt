package com.techtaurant.mainserver.user.infrastructure.out

import com.techtaurant.mainserver.user.entity.UserBan
import java.util.UUID

interface UserBanRepositoryCustom {
    fun findByUserIdAndBannedUserId(
        userId: UUID,
        bannedUserId: UUID,
    ): UserBan?

    fun findAllByUserIdOrderByCreatedAtDesc(userId: UUID): List<UserBan>

    fun findBannedUserIdsByUserId(userId: UUID): List<UUID>
}
