package com.techtaurant.mainserver.post.entity

import com.techtaurant.mainserver.common.base.EntityBase
import java.time.LocalDate

/**
 * 일별 게시물 통계 엔티티
 *
 * @property post 대상 게시물
 * @property statDate 통계 집계 일자
 * @property viewCount 해당 일자 조회 증분
 * @property likeCount 해당 일자 좋아요 증감
 * @property commentCount 해당 일자 댓글 증감
 */
class PostDailyStats(
    var post: Post,
    var statDate: LocalDate,
    var viewCount: Long = 0,
    var likeCount: Long = 0,
    var commentCount: Long = 0,
) : EntityBase()
