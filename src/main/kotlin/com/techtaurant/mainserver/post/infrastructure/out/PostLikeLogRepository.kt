package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.post.entity.PostLikeLog
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface PostLikeLogRepository : JpaRepository<PostLikeLog, UUID>, PostLikeLogRepositoryCustom
