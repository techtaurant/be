package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.Link
import com.techtaurant.mainserver.link.entity.UserLink
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface UserLinkRepository : JpaRepository<UserLink, UUID>, UserLinkRepositoryCustom {
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(
        """
        INSERT INTO user_links (id, user_id, link_id, created_at_utc, updated_at_utc)
        VALUES (:id, :userId, :linkId, NOW(), NOW())
        ON CONFLICT ON CONSTRAINT uk_user_links_user_id_link_id DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("id") id: UUID,
        @Param("userId") userId: UUID,
        @Param("linkId") linkId: UUID,
    ): Int

    fun deleteAllByLink(link: Link): Long
}
