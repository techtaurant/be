package com.techtaurant.mainserver.comment.infrastructure.out

import com.techtaurant.mainserver.comment.entity.CommentLikeLog
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface CommentLikeLogRepository : JpaRepository<CommentLikeLog, UUID>, CommentLikeLogRepositoryCustom
