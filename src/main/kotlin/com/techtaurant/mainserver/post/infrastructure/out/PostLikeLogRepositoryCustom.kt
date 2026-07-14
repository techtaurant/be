package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.post.entity.PostLikeLog
import java.util.UUID

interface PostLikeLogRepositoryCustom {
    fun findByPostIdAndUserId(
        postId: UUID,
        userId: UUID,
    ): PostLikeLog?

    fun findByPostIdAndUserIdForUpdate(
        postId: UUID,
        userId: UUID,
    ): PostLikeLog?

    fun findByUserIdAndPostIdIn(
        userId: UUID,
        postIds: List<UUID>,
    ): List<PostLikeLog>
}
