package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.post.entity.Category
import com.techtaurant.mainserver.user.entity.User
import java.util.UUID

interface CategoryRepositoryCustom {
    fun findByUserAndPath(
        user: User,
        path: String,
    ): Category?

    fun findByUserId(userId: UUID): List<Category>

    fun findByUserIdAndPathPrefix(
        userId: UUID,
        pathPrefix: String,
    ): List<Category>

    fun findByUserIdWithPostCount(userId: UUID): List<CategoryWithPostCountProjection>

    fun findByUserIdAndPathPrefixWithPostCount(
        userId: UUID,
        pathPrefix: String,
    ): List<CategoryWithPostCountProjection>
}
