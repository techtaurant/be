package com.techtaurant.mainserver.post.entity

import com.techtaurant.mainserver.common.base.EntityBase
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.user.entity.User
import java.util.UUID

/**
 * 게시물 엔티티
 *
 * @property title 게시물 제목 (최대 200자)
 * @property content 게시물 본문 (TEXT 타입, 제한 없음)
 * @property author 작성자 (User와 N:1 관계)
 * @property category 카테고리 (Category와 N:1 관계)
 * @property tags 태그 목록 (ManyToMany)
 * @property viewCount 조회수 (이벤트 발생 시 원자적 증분)
 * @property likeCount 좋아요수 (이벤트 발생 시 원자적 증분)
 * @property commentCount 댓글수 (이벤트 발생 시 원자적 증분)
 * @property thumbnailImage 게시물 썸네일 이미지 attachment ID
 * @property status 게시물 상태 (DRAFT: 임시저장, PUBLISHED: 발행, PRIVATE: 비공개)
 */
class Post(
    var title: String,
    var content: String,
    var author: User,
    var category: Category? = null,
    override var tags: MutableSet<Tag> = mutableSetOf(),
    var viewCount: Long = 0,
    var likeCount: Long = 0,
    var commentCount: Long = 0,
    var thumbnailImage: java.util.UUID? = null,
    var status: PostStatusEnum = PostStatusEnum.PUBLISHED,
) : EntityBase(), TaggedContent {
    init {
        validateTagCount()
    }

    /**
     * 본문이 참조하는 첨부 ID를 본문에 등장한 순서대로 반환합니다.
     * 첨부 유지 판정과 썸네일 fallback이 모두 이 목록을 기준으로 동작하므로,
     * 본문에서 참조가 사라진 첨부는 요청 목록에 남아 있어도 유지 대상이 아닙니다.
     */
    fun referencedAttachmentIds(): List<UUID> =
        ATTACHMENT_ID_PATTERN
            .findAll(content)
            .map { UUID.fromString(it.value) }
            .distinct()
            .toList()

    companion object {
        private val ATTACHMENT_ID_PATTERN =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
