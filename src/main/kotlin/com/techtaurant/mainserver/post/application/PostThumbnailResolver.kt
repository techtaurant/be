package com.techtaurant.mainserver.post.application

import com.techtaurant.mainserver.attachment.entity.Attachment
import com.techtaurant.mainserver.post.entity.Post
import org.springframework.stereotype.Component

/**
 * 게시물 목록/알림에 노출할 썸네일 첨부를 결정합니다.
 *
 * 작성자가 명시적으로 지정한 썸네일을 우선하고, 지정이 없으면 본문에서 가장 먼저 참조된 첨부를 사용합니다.
 * 둘 다 없으면 null을 반환하며, 기본 썸네일 URL로 대체하는 책임은 호출부에 있습니다.
 * 저장 시점에 썸네일을 자동으로 승격시키지 않고 조회 시점에 결정하므로,
 * 본문이 바뀌면 별도 갱신 없이 노출 썸네일도 함께 따라갑니다.
 */
@Component
class PostThumbnailResolver {
    fun resolve(
        post: Post,
        confirmedAttachments: List<Attachment>,
    ): Attachment? {
        val attachmentById = confirmedAttachments.associateBy { it.id }
        post.thumbnailImage?.let { attachmentById[it] }?.let { return it }

        return post.referencedAttachmentIds().firstNotNullOfOrNull { attachmentById[it] }
    }
}
