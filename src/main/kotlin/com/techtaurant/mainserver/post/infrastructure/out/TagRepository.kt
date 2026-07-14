package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.post.entity.Tag
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TagRepository : JpaRepository<Tag, UUID>, TagRepositoryCustom {
    override fun findByName(name: String): Tag?

    override fun findByNameIn(names: Collection<String>): List<Tag>

    override fun findAllWithPostCount(
        name: String?,
        limit: Int,
    ): List<TagWithPostCountProjection>

    /**
     * 커서 기반 페이지네이션으로 태그 목록 조회
     *
     * 정렬: postCount DESC, id ASC (게시물 많은 순, 동점이면 id 순)
     * 커서: 마지막으로 조회한 태그의 (postCount, id) 조합
     *
     * 다음 페이지 조건:
     * - postCount가 커서보다 작거나
     * - postCount가 같으면 id가 커서보다 큰 항목
     */
    override fun findAllWithPostCountAfterCursor(
        name: String?,
        lastPostCount: Long,
        lastTagId: UUID,
        limit: Int,
    ): List<TagWithPostCountProjection>
}
