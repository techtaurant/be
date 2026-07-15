package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.post.entity.PostViewLog
import java.util.UUID

interface PostViewLogRepositoryCustom {
    fun save(log: PostViewLog): PostViewLog

    fun findDistinctPostIdsByUserIdAndPostIdIn(
        userId: UUID,
        postIds: List<UUID>,
    ): List<UUID>
}
