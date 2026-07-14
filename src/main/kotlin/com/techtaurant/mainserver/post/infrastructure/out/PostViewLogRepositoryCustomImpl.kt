package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.jooq.tables.PostViewLog.Companion.POST_VIEW_LOG
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class PostViewLogRepositoryCustomImpl(
    private val dsl: DSLContext,
) : PostViewLogRepositoryCustom {
    override fun findDistinctPostIdsByUserIdAndPostIdIn(
        userId: UUID,
        postIds: List<UUID>,
    ): List<UUID> =
        if (postIds.isEmpty()) {
            emptyList()
        } else {
            dsl.selectDistinct(POST_VIEW_LOG.POST_ID)
                .from(POST_VIEW_LOG)
                .where(POST_VIEW_LOG.USER_ID.eq(userId).and(POST_VIEW_LOG.POST_ID.`in`(postIds)))
                .fetch(POST_VIEW_LOG.POST_ID)
                .filterNotNull()
        }
}
