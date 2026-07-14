package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.LinkLikeLog
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface LinkLikeLogRepository : JpaRepository<LinkLikeLog, UUID>, LinkLikeLogRepositoryCustom {
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(
        """
        INSERT INTO link_like_log (id, link_id, user_id, is_liked, created_at_utc, updated_at_utc)
        VALUES (:id, :linkId, :userId, :isLiked, NOW(), NOW())
        ON CONFLICT ON CONSTRAINT uk_link_like_log_link_id_user_id DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("id") id: UUID,
        @Param("linkId") linkId: UUID,
        @Param("userId") userId: UUID,
        @Param("isLiked") isLiked: Boolean,
    ): Int
}
