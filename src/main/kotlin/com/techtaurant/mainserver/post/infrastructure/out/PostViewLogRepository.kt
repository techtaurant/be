package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.post.entity.PostViewLog
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface PostViewLogRepository : JpaRepository<PostViewLog, UUID>, PostViewLogRepositoryCustom {
    /**
     * 특정 사용자가 조회한 게시글 ID 목록을 조회합니다.
     * 게시물 목록에서 사용자가 읽은 게시물을 표시하기 위해 사용됩니다.
     *
     * @param userId 사용자 ID
     * @param postIds 확인할 게시글 ID 목록
     * @return 사용자가 조회한 게시글 ID 목록
     */
    override fun findDistinctPostIdsByUserIdAndPostIdIn(
        userId: UUID,
        postIds: List<UUID>,
    ): List<UUID>
}
