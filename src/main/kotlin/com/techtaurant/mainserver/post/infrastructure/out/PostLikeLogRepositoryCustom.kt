package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.post.entity.PostLikeLog
import java.util.UUID

interface PostLikeLogRepositoryCustom {
    fun save(log: PostLikeLog): PostLikeLog

    fun delete(log: PostLikeLog)

    fun deleteAllInBatch()

    fun findById(id: UUID): java.util.Optional<PostLikeLog>

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
