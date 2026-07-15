package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.entity.Link
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.Repository
import java.time.Instant
import java.util.Optional
import java.util.UUID

interface LinkRepository : Repository<Link, UUID>, LinkRepositoryCustom {
    override fun save(link: Link): Link

    override fun saveAndFlush(link: Link): Link

    override fun delete(link: Link)

    override fun deleteAll()

    override fun deleteAllInBatch()

    override fun findAll(): List<Link>

    override fun findById(id: UUID): Optional<Link>

    override fun existsById(id: UUID): Boolean

    override fun findByUrl(url: String): Link?

    override fun findByIdWithTags(linkId: UUID): Link?

    override fun findAllWithTags(): List<Link>

    override fun findAllByConnectedUserIdWithTags(companyUserId: UUID): List<Link>

    override fun incrementViewCount(linkId: UUID)

    override fun incrementLikeCount(linkId: UUID)

    override fun decrementLikeCount(linkId: UUID)

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
