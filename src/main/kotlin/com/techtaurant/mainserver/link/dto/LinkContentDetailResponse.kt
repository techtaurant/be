package com.techtaurant.mainserver.link.dto

import com.techtaurant.mainserver.link.entity.Link
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "링크 정적 콘텐츠 상세 응답")
data class LinkContentDetailResponse(
    @field:Schema(description = "링크 ID")
    val id: UUID,
    @field:Schema(description = "제목")
    val title: String,
    @field:Schema(description = "원문 URL")
    val url: String,
    @field:Schema(description = "짧은 설명")
    val summary: String,
    @field:Schema(description = "최초 출처 사용자 ID", nullable = true)
    val sourceCompanyUserId: UUID?,
    @field:ArraySchema(schema = Schema(description = "링크 태그명", example = "engineering"))
    val tags: List<String>,
    @field:Schema(description = "링크 생성일")
    val createdAt: Instant,
    @field:Schema(description = "최종 수정일")
    val updatedAt: Instant,
    @field:Schema(description = "조회수 합계")
    val viewCount: Long,
    @field:Schema(description = "좋아요수 합계")
    val likeCount: Long,
    @field:Schema(description = "저장수 합계")
    val saveCount: Long,
) {
    companion object {
        fun from(
            link: Link,
            sourceCompanyUserId: UUID?,
            stats: LinkStatsResponse?,
        ): LinkContentDetailResponse =
            LinkContentDetailResponse(
                id = link.id ?: throw IllegalStateException("링크 ID가 없습니다"),
                title = link.title,
                url = link.url,
                summary = link.summary,
                sourceCompanyUserId = sourceCompanyUserId,
                tags =
                    link.tags
                        .map { it.name }
                        .sorted(),
                createdAt = link.createdAt,
                updatedAt = link.updatedAt,
                viewCount = stats?.viewCount ?: 0,
                likeCount = stats?.likeCount ?: 0,
                saveCount = stats?.saveCount ?: 0,
            )
    }
}
