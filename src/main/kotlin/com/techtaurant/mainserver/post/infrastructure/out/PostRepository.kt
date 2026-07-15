package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.post.entity.Post
import org.springframework.data.repository.Repository
import java.time.Instant
import java.util.Optional
import java.util.UUID

interface PostRepository : Repository<Post, UUID>, PostRepositoryCustom {
    override fun findById(id: UUID): Optional<Post>

    override fun findAllById(ids: Iterable<UUID>): List<Post>

    override fun existsById(id: UUID): Boolean

    override fun findAllByAuthorId(authorId: UUID): List<Post>

    /**
     * 게시물 상세 조회
     * author, tags, pictures, category를 JOIN FETCH하여 N+1 문제 방지
     * 모든 상태의 게시물을 조회하며, 권한 검증은 Service 레이어에서 수행
     *
     * @param postId 게시물 ID
     * @return 게시물 엔티티 (없으면 null)
     */
    override fun findPostDetailById(postId: UUID): Post?

    /**
     * 커서 기반 DRAFT 게시물 목록 조회
     * 최근 수정일 기준 내림차순 정렬
     * 커서가 없으면 첫 페이지를 조회하고, 커서가 있으면 해당 커서 이후 데이터를 조회합니다.
     *
     * @param authorId 작성자 ID
     * @param cursorUpdatedAt 커서 updatedAt (없으면 첫 페이지)
     * @param cursorId 커서 ID (updatedAt이 같을 때 구분용)
     * @param limit 조회 개수
     * @return DRAFT 게시물 리스트
     */
    override fun findDraftsByAuthorWithCursor(
        authorId: UUID,
        cursorUpdatedAt: Instant,
        cursorId: UUID,
        limit: Int,
    ): List<Post>

    /**
     * 커서 없이 DRAFT 게시물 첫 페이지 조회
     *
     * @param authorId 작성자 ID
     * @param limit 조회 개수
     * @return DRAFT 게시물 리스트
     */
    override fun findDraftsByAuthorFirstPage(
        authorId: UUID,
        limit: Int,
    ): List<Post>

    /**
     * 게시물 조회 with author (권한 검증용)
     * 상태 필터 없이 조회하여 Service에서 권한 검증
     *
     * @param postId 게시물 ID
     * @return 게시물 엔티티 (없으면 null)
     */
    override fun findPostByIdWithAuthor(postId: UUID): Post?

    /**
     * 공개 가능한 게시물 목록을 ID 목록으로 조회합니다.
     *
     * @param postIds 게시물 ID 목록
     * @return PUBLISHED 상태의 게시물 목록
     */
    override fun findPublishedPostsByIdIn(postIds: List<UUID>): List<Post>

    /**
     * 게시물의 조회수를 원자적으로 1 증가시킵니다.
     *
     * flushAutomatically: 쿼리 실행 '전' 쓰기 지연 저장소의 변경사항을 DB에 반영(동기화).
     * clearAutomatically: 쿼리 실행 '후' 1차 캐시를 비워, 이후 조회 시 DB의 최신 값을 보장.
     *
     * clearAutomatically = false 이유:
     * 트랜잭션 내 다른 엔티티의 영속성(Lazy Loading 등)을 유지해야 하거나,
     * 업데이트 후 재조회가 불필요하여 불필요한 캐시 초기화/재조회 비용을 아끼기 위함.
     *
     * @param postId 조회수를 증가시킬 게시물 ID
     */
    override fun incrementViewCount(postId: UUID)

    /**
     * 게시물의 좋아요수를 원자적으로 1 증가시킵니다.
     *
     * flushAutomatically: 쿼리 실행 '전' 쓰기 지연 저장소의 변경사항을 DB에 반영(동기화).
     * clearAutomatically: 쿼리 실행 '후' 1차 캐시를 비워, 이후 조회 시 DB의 최신 값을 보장.
     *
     * clearAutomatically = false 이유:
     * 트랜잭션 내 다른 엔티티의 영속성(Lazy Loading 등)을 유지해야 하거나,
     * 업데이트 후 재조회가 불필요하여 불필요한 캐시 초기화/재조회 비용을 아끼기 위함.
     *
     * @param postId 좋아요수를 증가시킬 게시물 ID
     */
    override fun incrementLikeCount(postId: UUID)

    /**
     * 게시물의 좋아요수를 원자적으로 1 감소시킵니다.
     *
     * flushAutomatically: 쿼리 실행 '전' 쓰기 지연 저장소의 변경사항을 DB에 반영(동기화).
     * clearAutomatically: 쿼리 실행 '후' 1차 캐시를 비워, 이후 조회 시 DB의 최신 값을 보장.
     *
     * clearAutomatically = false 이유:
     * 트랜잭션 내 다른 엔티티의 영속성(Lazy Loading 등)을 유지해야 하거나,
     * 업데이트 후 재조회가 불필요하여 불필요한 캐시 초기화/재조회 비용을 아끼기 위함.
     *
     * @param postId 좋아요수를 감소시킬 게시물 ID
     */
    override fun decrementLikeCount(postId: UUID)

    /**
     * 게시물의 댓글수를 원자적으로 1 증가시킵니다.
     *
     * flushAutomatically: 쿼리 실행 '전' 쓰기 지연 저장소의 변경사항을 DB에 반영(동기화).
     * clearAutomatically: 쿼리 실행 '후' 1차 캐시를 비워, 이후 조회 시 DB의 최신 값을 보장.
     *
     * clearAutomatically = false 이유:
     * 트랜잭션 내 다른 엔티티의 영속성(Lazy Loading 등)을 유지해야 하거나,
     * 업데이트 후 재조회가 불필요하여 불필요한 캐시 초기화/재조회 비용을 아끼기 위함.
     *
     *
     * @param postId 댓글수를 증가시킬 게시물 ID
     */
    override fun incrementCommentCount(postId: UUID)

    /**
     * 게시물의 댓글수를 원자적으로 1 감소시킵니다.
     *
     * @param postId 댓글수를 감소시킬 게시물 ID
     */
    override fun decrementCommentCount(postId: UUID)

    /**
     * 특정 사용자의 2주 이상 경과한 DRAFT 게시물 목록을 조회합니다.
     *
     * @param authorId 작성자 ID
     * @param before 기준 날짜 (이 날짜 이전에 수정된 DRAFT 반환)
     * @return 만료된 DRAFT 게시물 리스트
     */
    override fun findStaleDraftsByAuthor(
        authorId: UUID,
        before: Instant,
    ): List<Post>
}
