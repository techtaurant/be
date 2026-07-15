package com.techtaurant.mainserver.user.infrastructure.out

import com.techtaurant.mainserver.user.entity.UserBan
import org.springframework.data.repository.Repository
import java.util.UUID

interface UserBanRepository : Repository<UserBan, UUID>, UserBanRepositoryCustom {
    override fun findByUserIdAndBannedUserId(
        userId: UUID,
        bannedUserId: UUID,
    ): UserBan?

    override fun findAllByUserIdOrderByCreatedAtDesc(userId: UUID): List<UserBan>

    override fun findBannedUserIdsByUserId(userId: UUID): List<UUID>
}
