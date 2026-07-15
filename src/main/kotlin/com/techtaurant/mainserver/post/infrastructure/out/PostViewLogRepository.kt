package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.post.entity.PostViewLog
import org.springframework.data.repository.Repository
import java.util.UUID

interface PostViewLogRepository : Repository<PostViewLog, UUID>, PostViewLogRepositoryCustom {
    override fun save(log: PostViewLog): PostViewLog

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
