package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.post.entity.PostReadLog
import org.springframework.data.repository.Repository
import java.util.UUID

interface PostReadLogRepository : Repository<PostReadLog, UUID>, PostReadLogRepositoryCustom {
    override fun save(log: PostReadLog): PostReadLog

    override fun delete(log: PostReadLog)

    override fun deleteAllInBatch()

    override fun findById(id: UUID): java.util.Optional<PostReadLog>

    /**
     * 특정 게시물에 대한 특정 사용자의 읽음 기록을 조회합니다.
     *
     * @param postId 게시물 ID
     * @param userId 사용자 ID
     * @return 읽음 기록 (없으면 null)
     */
    override fun findByPostIdAndUserId(
        postId: UUID,
        userId: UUID,
    ): PostReadLog?

    /**
     * 특정 게시물에 대한 특정 사용자의 읽음 기록 존재 여부를 확인합니다.
     *
     * @param postId 게시물 ID
     * @param userId 사용자 ID
     * @return 읽음 기록 존재 여부
     */
    override fun existsByPostIdAndUserId(
        postId: UUID,
        userId: UUID,
    ): Boolean

    /**
     * 특정 사용자의 여러 게시물에 대한 읽음 기록을 일괄 조회합니다.
     * 게시물 목록에서 읽음 여부를 확인할 때 사용합니다.
     *
     * @param userId 사용자 ID
     * @param postIds 게시물 ID 목록
     * @return 읽음 기록 목록
     */
    override fun findByUserIdAndPostIdIn(
        userId: UUID,
        postIds: List<UUID>,
    ): List<PostReadLog>
}
