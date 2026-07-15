package com.techtaurant.mainserver.post.entity

import com.techtaurant.mainserver.common.base.EntityBase
import com.techtaurant.mainserver.user.entity.User

/**
 * 게시글 조회 이벤트 로그 엔티티
 * 각 조회 이벤트를 기록하여 실시간 통계 집계에 사용합니다.
 *
 * @property post 조회된 게시글
 * @property user 조회한 사용자 (비회원 조회는 null)
 * @property ipAddress 조회한 IP 주소
 * @property userAgent 브라우저 User-Agent
 */
class PostViewLog(
    var post: Post,
    var user: User? = null,
    var ipAddress: String? = null,
    var userAgent: String? = null,
) : EntityBase()
