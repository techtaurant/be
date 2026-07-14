package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.jooq.tables.LinkViewLog.Companion.LINK_VIEW_LOG
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class LinkViewLogRepositoryCustomImpl(
    private val dsl: DSLContext,
) : LinkViewLogRepositoryCustom {
    override fun findDistinctLinkIdsByUserIdAndLinkIdIn(
        userId: UUID,
        linkIds: List<UUID>,
    ): List<UUID> =
        if (linkIds.isEmpty()) {
            emptyList()
        } else {
            dsl.selectDistinct(LINK_VIEW_LOG.LINK_ID)
                .from(LINK_VIEW_LOG)
                .where(LINK_VIEW_LOG.USER_ID.eq(userId).and(LINK_VIEW_LOG.LINK_ID.`in`(linkIds)))
                .fetch(LINK_VIEW_LOG.LINK_ID)
                .filterNotNull()
        }
}
