package com.techtaurant.mainserver.post.entity

import com.techtaurant.mainserver.common.base.EntityBase
import com.techtaurant.mainserver.user.entity.User
import java.util.UUID

/**
 * 게시물 읽음 표시 기록 엔티티
 * 사용자가 명시적으로 게시물을 읽었다고 표시한 기록을 저장합니다.
 * 레코드가 존재하면 읽음, 존재하지 않으면 안읽음 상태입니다.
 *
 * @property postId 읽음 표시한 게시물 ID
 * @property user 읽음 표시한 사용자
 */
class PostReadLog(
    var postId: UUID,
    var user: User,
) : EntityBase()
