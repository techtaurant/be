package com.techtaurant.mainserver.user.infrastructure.out

import com.techtaurant.mainserver.user.entity.UserFollow
import java.util.UUID

interface UserFollowRepositoryCustom {
    fun findByFollowerIdAndFollowingId(
        followerId: UUID,
        followingId: UUID,
    ): UserFollow?

    fun countByFollowerId(followerId: UUID): Long

    fun countByFollowingId(followingId: UUID): Long

    fun findAllByFollowerIdOrderByCreatedAtDesc(followerId: UUID): List<UserFollow>

    fun findAllByFollowingIdOrderByCreatedAtDesc(followingId: UUID): List<UserFollow>

    fun findFollowerIdsByFollowingId(followingId: UUID): List<UUID>
}
