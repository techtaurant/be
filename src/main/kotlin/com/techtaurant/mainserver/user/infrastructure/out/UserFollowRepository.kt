package com.techtaurant.mainserver.user.infrastructure.out

import com.techtaurant.mainserver.user.entity.UserFollow
import org.springframework.data.repository.Repository
import java.util.UUID

interface UserFollowRepository : Repository<UserFollow, UUID>, UserFollowRepositoryCustom {
    override fun findByFollowerIdAndFollowingId(
        followerId: UUID,
        followingId: UUID,
    ): UserFollow?

    override fun countByFollowerId(followerId: UUID): Long

    override fun countByFollowingId(followingId: UUID): Long

    override fun findAllByFollowerIdOrderByCreatedAtDesc(followerId: UUID): List<UserFollow>

    override fun findAllByFollowingIdOrderByCreatedAtDesc(followingId: UUID): List<UserFollow>

    override fun findFollowerIdsByFollowingId(followingId: UUID): List<UUID>

    override fun deleteMutualFollows(
        firstUserId: UUID,
        secondUserId: UUID,
    ): Int
}
