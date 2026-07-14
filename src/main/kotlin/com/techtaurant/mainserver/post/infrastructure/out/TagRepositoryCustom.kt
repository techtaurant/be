package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.post.entity.Tag
import java.util.UUID

interface TagRepositoryCustom {
    fun findByName(name: String): Tag?

    fun findByNameIn(names: Collection<String>): List<Tag>

    fun findAllWithPostCount(
        name: String?,
        limit: Int,
    ): List<TagWithPostCountProjection>

    fun findAllWithPostCountAfterCursor(
        name: String?,
        lastPostCount: Long,
        lastTagId: UUID,
        limit: Int,
    ): List<TagWithPostCountProjection>
}
