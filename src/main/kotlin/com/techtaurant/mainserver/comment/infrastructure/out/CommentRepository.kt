package com.techtaurant.mainserver.comment.infrastructure.out

import com.techtaurant.mainserver.comment.entity.Comment
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional
import java.util.UUID

interface CommentRepository : JpaRepository<Comment, UUID>, CommentRepositoryCustom {
    override fun findById(id: UUID): Optional<Comment>

    override fun existsById(id: UUID): Boolean

    /**
     * 게시물의 삭제되지 않은 댓글을 생성 시간 오름차순으로 조회합니다.
     * 대댓글은 부모 댓글 다음에 오도록 정렬됩니다.
     *
     * @param postId 게시물 ID
     * @return 댓글 목록 (생성 시간 오름차순)
     */
    override fun findByPostIdAndDeletedAtIsNullOrderByCreatedAtAsc(postId: UUID): List<Comment>

    /**
     * 댓글의 좋아요수를 원자적으로 1 증가시킵니다.
     *
     * flushAutomatically: 쿼리 실행 '전' 쓰기 지연 저장소의 변경사항을 DB에 반영(동기화).
     * clearAutomatically: 쿼리 실행 '후' 1차 캐시를 비워, 이후 조회 시 DB의 최신 값을 보장.
     *
     * clearAutomatically = false 이유:
     * 트랜잭션 내 다른 엔티티의 영속성(Lazy Loading 등)을 유지해야 하거나,
     * 업데이트 후 재조회가 불필요하여 불필요한 캐시 초기화/재조회 비용을 아끼기 위함.
     *
     * @param commentId 좋아요수를 증가시킬 댓글 ID
     */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("UPDATE Comment c SET c.likeCount = c.likeCount + 1 WHERE c.id = :commentId")
    fun incrementLikeCount(
        @Param("commentId") commentId: UUID,
    )

    /**
     * 댓글의 좋아요수를 원자적으로 1 감소시킵니다.
     *
     * flushAutomatically: 쿼리 실행 '전' 쓰기 지연 저장소의 변경사항을 DB에 반영(동기화).
     * clearAutomatically: 쿼리 실행 '후' 1차 캐시를 비워, 이후 조회 시 DB의 최신 값을 보장.
     *
     * clearAutomatically = false 이유:
     * 트랜잭션 내 다른 엔티티의 영속성(Lazy Loading 등)을 유지해야 하거나,
     * 업데이트 후 재조회가 불필요하여 불필요한 캐시 초기화/재조회 비용을 아끼기 위함.
     *
     * @param commentId 좋아요수를 감소시킬 댓글 ID
     */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("UPDATE Comment c SET c.likeCount = c.likeCount - 1 WHERE c.id = :commentId")
    fun decrementLikeCount(
        @Param("commentId") commentId: UUID,
    )

    /**
     * 부모 댓글의 대댓글 수를 원자적으로 1 증가시킵니다.
     *
     * @param commentId 부모 댓글 ID
     */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("UPDATE Comment c SET c.replyCount = c.replyCount + 1 WHERE c.id = :commentId")
    fun incrementReplyCount(
        @Param("commentId") commentId: UUID,
    )

    /**
     * 부모 댓글의 대댓글 수를 원자적으로 1 감소시킵니다.
     *
     * @param commentId 부모 댓글 ID
     */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("UPDATE Comment c SET c.replyCount = CASE WHEN c.replyCount > 0 THEN c.replyCount - 1 ELSE 0 END WHERE c.id = :commentId")
    fun decrementReplyCount(
        @Param("commentId") commentId: UUID,
    )
}
