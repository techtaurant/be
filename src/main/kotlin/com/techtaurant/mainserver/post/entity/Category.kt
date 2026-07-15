package com.techtaurant.mainserver.post.entity

import com.techtaurant.mainserver.common.base.EntityBase
import com.techtaurant.mainserver.user.entity.User

/**
 * 계층형 카테고리 엔티티
 * 최대 5단계 depth까지 지원하며, 경로 기반으로 카테고리를 관리합니다.
 * 각 유저별로 동일한 path를 가질 수 없습니다.
 *
 * @property user 카테고리 소유자
 * @property name 카테고리 이름 (예: "spring")
 * @property path 전체 경로 (예: "java/spring/deepdive")
 * @property depth 현재 depth (1~5)
 * @property parent 부모 카테고리 (nullable)
 */
class Category(
    var user: User,
    var name: String,
    var path: String,
    var depth: Int,
    var parent: Category? = null,
    var children: MutableList<Category> = mutableListOf(),
) : EntityBase()
