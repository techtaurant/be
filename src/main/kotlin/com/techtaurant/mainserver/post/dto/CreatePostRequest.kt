package com.techtaurant.mainserver.post.dto

import com.techtaurant.mainserver.post.entity.TaggedContent
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/**
 * 게시물 생성 요청 DTO
 *
 * @property title 게시물 제목 (DRAFT는 선택, 나머지는 필수)
 * @property content 게시물 본문 (DRAFT는 선택, 나머지는 필수)
 * @property categoryPath 카테고리 경로 (선택, 예: "java/spring/deepdive")
 * @property tags 태그 목록 (선택, 여러 개 가능)
 * @property status 게시물 상태 (DRAFT: 임시저장, PUBLISHED: 발행, PRIVATE: 비공개, 기본값: PUBLISHED)
 * @property createdAt 게시물 생성일시 (선택, 입력하지 않으면 서버 시간이 사용됨)
 */
@Schema(description = "게시물 생성 요청")
data class CreatePostRequest(
    @field:Size(max = 200, message = "제목은 최대 200자까지 가능합니다")
    @field:Schema(description = "게시물 제목 (DRAFT는 선택, 나머지는 필수)", example = "Spring Boot 시작하기", maxLength = 200)
    val title: String? = null,
    @field:Schema(description = "게시물 본문 (DRAFT는 선택, 나머지는 필수)", example = "Spring Boot를 사용하면...")
    val content: String? = null,
    @field:Schema(description = "카테고리 경로 (슬래시로 구분, 최대 5단계)", example = "java/spring/deepdive")
    val categoryPath: String? = null,
    @field:Size(max = TaggedContent.MAX_TAG_COUNT, message = "태그는 최대 10개까지 설정할 수 있습니다")
    @field:ArraySchema(maxItems = TaggedContent.MAX_TAG_COUNT, schema = Schema(description = "태그명", example = "spring"))
    val tags: List<String>? = null,
    @field:Schema(description = "게시물에 연결할 attachment ID 목록", example = "[\"01234567-89ab-cdef-0123-456789abcdef\"]")
    val attachmentIds: List<UUID>? = null,
    @field:Schema(
        description = "대표 썸네일로 사용할 attachment ID (DRAFT도 저장되며, 이때 첨부는 확정되지 않고 tmp 경로에 남는다)",
        example = "01234567-89ab-cdef-0123-456789abcdef",
    )
    val thumbnailAttachmentId: UUID? = null,
    @field:Schema(description = "게시물 상태 (DRAFT/PUBLISHED/PRIVATE, 기본값: PUBLISHED)", example = "PUBLISHED")
    val status: PostStatusEnum? = null,
    @field:Schema(description = "게시물 생성일시 (ISO-8601 UTC, 입력하지 않으면 서버 시간이 사용됨)", example = "2026-04-25T10:15:30Z")
    val createdAt: Instant? = null,
)
