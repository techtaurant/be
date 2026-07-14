package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.Link
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.Optional
import java.util.UUID

interface LinkRepository : JpaRepository<Link, UUID>, LinkRepositoryCustom {
    override fun findById(id: UUID): Optional<Link>

    override fun existsById(id: UUID): Boolean

    override fun findByUrl(url: String): Link?

    override fun findByIdWithTags(linkId: UUID): Link?

    override fun findAllWithTags(): List<Link>

    override fun findAllByConnectedUserIdWithTags(companyUserId: UUID): List<Link>

    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("UPDATE Link l SET l.viewCount = l.viewCount + 1 WHERE l.id = :linkId")
    fun incrementViewCount(
        @Param("linkId") linkId: UUID,
    )

    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("UPDATE Link l SET l.likeCount = l.likeCount + 1 WHERE l.id = :linkId")
    fun incrementLikeCount(
        @Param("linkId") linkId: UUID,
    )

    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("UPDATE Link l SET l.likeCount = l.likeCount - 1 WHERE l.id = :linkId")
    fun decrementLikeCount(
        @Param("linkId") linkId: UUID,
    )

    override fun findFirstPageIds(
        sourceCompanyUserId: UUID?,
        tag: String?,
        pageable: Pageable,
    ): List<UUID>

    override fun findNextPageIds(
        sourceCompanyUserId: UUID?,
        tag: String?,
        cursorCreatedAt: Instant,
        cursorId: UUID,
        pageable: Pageable,
    ): List<UUID>

    override fun findAllByIdInWithTags(linkIds: List<UUID>): List<Link>
}
