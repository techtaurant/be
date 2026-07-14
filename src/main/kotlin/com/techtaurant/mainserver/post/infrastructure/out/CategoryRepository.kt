package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.post.entity.Category
import com.techtaurant.mainserver.user.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CategoryRepository : JpaRepository<Category, UUID>, CategoryRepositoryCustom {
    override fun findByUserAndPath(
        user: User,
        path: String,
    ): Category?

    /**
     * 유저의 전체 카테고리 조회
     */
    override fun findByUserId(userId: UUID): List<Category>

    /**
     * 유저의 특정 path prefix로 시작하는 카테고리 조회
     * Native query + LIKE 'prefix%' 패턴으로 B-tree 인덱스(idx_category_user_path_prefix) 활용
     */
    override fun findByUserIdAndPathPrefix(
        userId: UUID,
        pathPrefix: String,
    ): List<Category>

    /**
     * 유저의 전체 카테고리를 게시물 수와 함께 조회
     * 각 카테고리 자신과 하위 카테고리에 속한 게시물 수를 함께 집계한다.
     */
    override fun findByUserIdWithPostCount(userId: UUID): List<CategoryWithPostCountProjection>

    /**
     * 유저의 특정 path prefix로 시작하는 카테고리를 게시물 수와 함께 조회
     * 각 카테고리 자신과 하위 카테고리에 속한 게시물 수를 함께 집계한다.
     */
    override fun findByUserIdAndPathPrefixWithPostCount(
        userId: UUID,
        pathPrefix: String,
    ): List<CategoryWithPostCountProjection>
}
