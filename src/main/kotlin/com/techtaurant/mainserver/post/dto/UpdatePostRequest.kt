package com.techtaurant.mainserver.post.dto

import com.techtaurant.mainserver.post.entity.TaggedContent
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * 게시물 수정 요청 DTO
 *
 * 모든 필드가 선택적이며, 포함된 필드만 업데이트됩니다.
 * 상태 전환 시 DRAFT를 제외한 상태는 제목과 본문이 필수입니다.
 *
 * @property title 게시물 제목 (선택, 최대 200자)
 * @property content 게시물 본문 (선택, 변경 시 본문에서 참조가 사라진 기존 첨부 제거)
 * @property categoryPath 카테고리 경로 (선택)
 * @property tags 태그 목록 (선택)
 * @property attachmentIds 새로 확정할 첨부 ID 목록 (유지·삭제 판단은 항상 본문 참조 기준)
 * @property thumbnailAttachmentId 대표 썸네일 첨부 ID (생략 시 유지, 지정된 적 없으면 본문 첫 첨부로 노출)
 * @property status 게시물 상태 (선택, DRAFT/PUBLISHED/PRIVATE)
 */
@Schema(description = "게시물 수정 요청")
data class UpdatePostRequest(
    @field:Size(max = 200, message = "제목은 최대 200자까지 가능합니다")
    @field:Schema(description = "게시물 제목", example = "Spring Boot 시작하기", maxLength = 200)
    val title: String? = null,
    @field:Schema(description = "게시물 본문 (변경 시 본문에서 참조가 사라진 기존 첨부 제거)", example = "Spring Boot를 사용하면...")
    val content: String? = null,
    @field:Schema(description = "카테고리 경로 (슬래시로 구분, 최대 5단계)", example = "java/spring/deepdive")
    val categoryPath: String? = null,
    @field:Size(max = TaggedContent.MAX_TAG_COUNT, message = "태그는 최대 10개까지 설정할 수 있습니다")
    @field:ArraySchema(maxItems = TaggedContent.MAX_TAG_COUNT, schema = Schema(description = "태그명", example = "spring"))
    val tags: List<String>? = null,
    @field:Schema(
        description = "새로 확정할 attachment ID 목록 (본문에 참조가 있는 항목만 확정되며, 첨부 유지·삭제는 목록과 무관하게 본문 참조 기준으로 판단)",
        example = "[\"01234567-89ab-cdef-0123-456789abcdef\"]",
    )
    val attachmentIds: List<UUID>? = null,
    @field:Schema(
        description = "대표 썸네일로 사용할 attachment ID (생략 시 기존 썸네일 유지, 지정된 적 없으면 본문 첫 첨부로 노출)",
        example = "01234567-89ab-cdef-0123-456789abcdef",
    )
    val thumbnailAttachmentId: UUID? = null,
    @field:Schema(description = "게시물 상태 (DRAFT/PUBLISHED/PRIVATE)", example = "PUBLISHED")
    val status: PostStatusEnum? = null,
)
