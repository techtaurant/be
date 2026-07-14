package com.techtaurant.mainserver.user.infrastructure.out

import com.techtaurant.mainserver.user.entity.UserFollow
import jakarta.transaction.Transactional
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface UserFollowRepository : JpaRepository<UserFollow, UUID>, UserFollowRepositoryCustom {
    override fun findByFollowerIdAndFollowingId(
        followerId: UUID,
        followingId: UUID,
    ): UserFollow?

    override fun countByFollowerId(followerId: UUID): Long

    override fun countByFollowingId(followingId: UUID): Long

    override fun findAllByFollowerIdOrderByCreatedAtDesc(followerId: UUID): List<UserFollow>

    override fun findAllByFollowingIdOrderByCreatedAtDesc(followingId: UUID): List<UserFollow>

    override fun findFollowerIdsByFollowingId(followingId: UUID): List<UUID>

    @Modifying
    @Transactional
    @Query(
        """
        DELETE FROM UserFollow uf
        WHERE (uf.follower.id = :firstUserId AND uf.following.id = :secondUserId)
           OR (uf.follower.id = :secondUserId AND uf.following.id = :firstUserId)
        """,
    )
    fun deleteMutualFollows(
        @Param("firstUserId") firstUserId: UUID,
        @Param("secondUserId") secondUserId: UUID,
    ): Int
}
