package com.techtaurant.mainserver.post.infrastructure.out

import java.util.UUID

interface PostViewLogRepositoryCustom {
    fun findDistinctPostIdsByUserIdAndPostIdIn(
        userId: UUID,
        postIds: List<UUID>,
    ): List<UUID>
}
