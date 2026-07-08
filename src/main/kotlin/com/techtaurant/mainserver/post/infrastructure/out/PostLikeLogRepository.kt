package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.post.entity.PostLikeLog
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.*

interface PostLikeLogRepository : JpaRepository<PostLikeLog, UUID> {
    /**
     * 특정 게시글에 대한 특정 사용자의 좋아요 로그를 조회합니다.
     * 기존 로그가 있는지 확인하여 UPDATE 또는 INSERT를 결정하는 데 사용됩니다.
     *
     * @param postId 게시글 ID
     * @param userId 사용자 ID
     * @return 좋아요 로그 (없으면 null)
     */
    fun findByPostIdAndUserId(
        postId: UUID,
        userId: UUID,
    ): PostLikeLog?

    /**
     * 특정 게시글·사용자의 좋아요 로그를 행 잠금(PESSIMISTIC_WRITE)으로 조회합니다.
     * 좋아요 상태 전이를 직렬화하여 동시 요청(따닥) 시 중복 알림/중복 카운트를 방지합니다.
     *
     * @param postId 게시글 ID
     * @param userId 사용자 ID
     * @return 좋아요 로그 (없으면 null)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM PostLikeLog l WHERE l.post.id = :postId AND l.user.id = :userId")
    fun findByPostIdAndUserIdForUpdate(
        @Param("postId") postId: UUID,
        @Param("userId") userId: UUID,
    ): PostLikeLog?

    /**
     * 특정 사용자의 여러 게시글 좋아요 로그를 일괄 조회합니다.
     *
     * @param userId 사용자 ID
     * @param postIds 게시물 ID 목록
     * @return 좋아요 로그 목록
     */
    fun findByUserIdAndPostIdIn(
        userId: UUID,
        postIds: List<UUID>,
    ): List<PostLikeLog>
}
