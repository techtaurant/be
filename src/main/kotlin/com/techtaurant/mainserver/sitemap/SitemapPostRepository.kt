package com.techtaurant.mainserver.sitemap

import com.techtaurant.mainserver.jooq.tables.Posts.Companion.POSTS
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

data class SitemapPost(val id: UUID, val updatedAt: Instant)

@Repository
class SitemapPostRepository(private val dsl: DSLContext) {
    // A single bounded SELECT gives one PostgreSQL statement snapshot; never load post bodies.
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
