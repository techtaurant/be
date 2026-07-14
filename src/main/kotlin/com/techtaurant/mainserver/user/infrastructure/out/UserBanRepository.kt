package com.techtaurant.mainserver.user.infrastructure.out

import com.techtaurant.mainserver.user.entity.UserBan
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserBanRepository : JpaRepository<UserBan, UUID>, UserBanRepositoryCustom {
    override fun findByUserIdAndBannedUserId(
        userId: UUID,
        bannedUserId: UUID,
    ): UserBan?

    override fun findAllByUserIdOrderByCreatedAtDesc(userId: UUID): List<UserBan>

    override fun findBannedUserIdsByUserId(userId: UUID): List<UUID>
}
