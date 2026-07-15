package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.post.entity.PostLikeLog
import org.springframework.data.repository.Repository
import java.util.UUID

interface PostLikeLogRepository : Repository<PostLikeLog, UUID>, PostLikeLogRepositoryCustom {
    override fun save(log: PostLikeLog): PostLikeLog

    override fun delete(log: PostLikeLog)

    override fun deleteAllInBatch()

    override fun findById(id: UUID): java.util.Optional<PostLikeLog>
}
