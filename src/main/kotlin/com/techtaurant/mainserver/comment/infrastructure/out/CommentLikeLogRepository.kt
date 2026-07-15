package com.techtaurant.mainserver.comment.infrastructure.out

import com.techtaurant.mainserver.comment.entity.CommentLikeLog
import org.springframework.data.repository.Repository
import java.util.UUID

interface CommentLikeLogRepository : Repository<CommentLikeLog, UUID>, CommentLikeLogRepositoryCustom {
    override fun save(log: CommentLikeLog): CommentLikeLog

    override fun delete(log: CommentLikeLog)
}
