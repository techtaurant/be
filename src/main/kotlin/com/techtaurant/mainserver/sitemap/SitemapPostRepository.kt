package com.techtaurant.mainserver.sitemap

import com.techtaurant.mainserver.jooq.tables.Posts.Companion.POSTS
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

data class SitemapPost(val id: UUID, val updatedAt: Instant)

/**
 * 사이트맵에 필요한 공개 게시글 ID와 수정 시각을 조회합니다.
 * 게시글 본문과 연관 엔티티는 조회하지 않습니다.
 */
@Repository
class SitemapPostRepository(private val dsl: DSLContext) {
    /**
     * PUBLISHED 게시글을 ID 순서로 최대 50,001건 조회합니다.
     *
     * 청크나 페이지별 조회가 아니라 단일 SELECT 결과를 메모리의 목록으로 반환합니다.
     * URL 한도를 넘었는지 판별할 수 있도록 [SitemapXmlWriter.MAX_URLS]보다 한 건 더 조회하며,
     * 초과 여부는 XML 생성 단계에서 검사합니다.
     *
     * @return 공개 게시글의 ID와 DB에 저장된 실제 수정 시각
     */
    fun findPublished(): List<SitemapPost> =
        dsl.select(POSTS.ID, POSTS.UPDATED_AT_UTC)
            .from(POSTS)
            .where(POSTS.STATUS.eq(PostStatusEnum.PUBLISHED.name))
            .orderBy(POSTS.ID)
            .limit(SitemapXmlWriter.MAX_URLS + 1)
            .queryTimeout(QUERY_TIMEOUT_SECONDS)
            .fetch { SitemapPost(requireNotNull(it.value1()), requireNotNull(it.value2()).toInstant()) }

    private companion object {
        const val QUERY_TIMEOUT_SECONDS = 30
    }
}
