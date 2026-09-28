package com.techtaurant.mainserver.post.entity

/**
 * 게시물 정렬 타입
 *
 * 시간 기준 정렬은 둘이다. LATEST는 작성일, UPDATED는 수정일을 기준으로 하므로
 * 오래된 글을 고쳐도 최신순 상단에는 올라오지 않고 최근 수정순에서만 위로 올라온다.
 *
 * @property field 정렬 기준 필드명
 */
enum class PostSortType(val field: String) {
    LATEST("createdAt"),
    UPDATED("updatedAt"),
    VIEW("viewCount"),
    LIKE("likeCount"),
    COMMENT("commentCount"),
    ;

    companion object {
        fun fromString(value: String?): PostSortType = entries.find { it.name.equals(value, ignoreCase = true) } ?: LATEST
    }
}
