package com.techtaurant.mainserver.link.dto

import com.techtaurant.mainserver.link.application.LinkSnapshot
import com.techtaurant.mainserver.link.entity.Link
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

@Schema(description = "실패 잡 링크 직접 등록 요청. URL은 실패 잡에 기록된 아티클 URL을 사용합니다")
data class RegisterFailedJobLinkRequest(
    @field:NotBlank(message = "title은 필수입니다")
    @field:Size(max = Link.TITLE_MAX_LENGTH, message = "title은 최대 ${Link.TITLE_MAX_LENGTH}자까지 가능합니다")
    @field:Schema(description = "링크 제목", example = "수동으로 등록한 아티클")
    val title: String,
    @field:Schema(description = "링크 요약", example = "크롤러가 수집하지 못해 관리자가 직접 입력한 요약")
    val summary: String = "",
    @field:Schema(description = "링크 발행 시각", example = "2026-09-16T00:00:00Z")
    val createdAt: Instant,
) {
    fun toLinkSnapshot(articleUrl: String): LinkSnapshot =
        LinkSnapshot(
            title = title,
            url = articleUrl,
            summary = summary,
            createdAt = createdAt,
        )
}
