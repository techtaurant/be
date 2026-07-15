package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.post.entity.PostReadLog
import java.util.UUID

interface PostReadLogRepositoryCustom {
    fun save(log: PostReadLog): PostReadLog

    fun delete(log: PostReadLog)

    fun deleteAllInBatch()

    fun findById(id: UUID): java.util.Optional<PostReadLog>

    fun findByPostIdAndUserId(
        postId: UUID,
        userId: UUID,
    ): PostReadLog?

    fun existsByPostIdAndUserId(
        postId: UUID,
        userId: UUID,
    ): Boolean

    fun findByUserIdAndPostIdIn(
        userId: UUID,
        postIds: List<UUID>,
    ): List<PostReadLog>
}
