package com.techtaurant.mainserver.post.entity

import com.techtaurant.mainserver.common.base.EntityBase
import com.techtaurant.mainserver.user.entity.User

/**
 * 게시글 좋아요 이벤트 로그 엔티티
 * 좋아요/취소 이벤트를 기록하여 실시간 통계 집계에 사용합니다.
 * 한 사용자는 같은 게시글에 대해 하나의 좋아요 상태만 가질 수 있습니다.
 *
 * @property post 좋아요된 게시글
 * @property user 좋아요한 사용자
 * @property isLiked TRUE: 좋아요, FALSE: 좋아요 취소
 */
class PostLikeLog(
    var post: Post,
    var user: User,
    var isLiked: Boolean = true,
) : EntityBase()
