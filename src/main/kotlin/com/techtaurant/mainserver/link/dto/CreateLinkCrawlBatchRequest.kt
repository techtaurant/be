package com.techtaurant.mainserver.link.dto

import com.fasterxml.jackson.annotation.JsonIgnore
import com.techtaurant.mainserver.post.entity.TaggedContent
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "링크 수집 배치 등록 요청")
data class CreateLinkCrawlBatchRequest(
    @field:NotBlank(message = "배치 이름은 필수입니다")
    @field:Size(max = 100, message = "배치 이름은 최대 100자까지 가능합니다")
    @field:Schema(description = "배치 이름", example = "토스 테크 링크 수집")
    val name: String,
    @field:NotBlank(message = "baseUrl은 필수입니다")
    @field:Schema(description = "크롤링 기준 base URL", example = "https://toss.tech")
    val baseUrl: String,
    @field:NotBlank(message = "pageUriTemplate은 필수입니다")
    @field:Schema(description = "페이지 URI 템플릿. {page} 치환자를 사용합니다.", example = "/?page={page}")
    val pageUriTemplate: String,
    @field:NotBlank(message = "itemSelector는 필수입니다")
    @field:Schema(
        description = "목록 페이지에서 각 게시물 카드 전체를 선택하는 selector",
        example = "a[data-log-name='item'][data-log-section_type='new'][data-log-item_type='article'][href^='/article/']",
    )
    val itemSelector: String,
    @field:NotBlank(message = "articleLinkSelector는 필수입니다")
    @field:Schema(description = "itemSelector로 찾은 카드 내부에서 링크를 선택하는 selector. 카드 자체가 a 태그면 :self 사용 가능", example = ":self")
    val articleLinkSelector: String,
    @field:NotBlank(message = "titleSelector는 필수입니다")
    @field:Schema(description = "카드 내부 제목 selector", example = "div._13swo3b7")
    val titleSelector: String,
    @field:Schema(description = "카드 내부 요약 selector", example = "div._13swo3b8", nullable = true)
    val summarySelector: String? = null,
    @field:ArraySchema(
        schema =
            Schema(
                description = "링크 생성일 selector. ISO 날짜/시간, '2023년 6월 20일', '2023. 6. 20', '2023/6/20' 형식의 텍스트를 읽습니다.",
                example = "div.o6bzluc",
            ),
    )
    val createdAtSelectors: List<String> = emptyList(),
    @field:Size(max = TaggedContent.MAX_TAG_COUNT, message = "태그는 최대 10개까지 설정할 수 있습니다")
    @field:ArraySchema(maxItems = TaggedContent.MAX_TAG_COUNT, schema = Schema(description = "수집된 링크에 부여할 태그명", example = "toss-tech"))
    val tagNames: List<String> = emptyList(),
    @field:NotBlank(message = "cronExpression은 필수입니다")
    @field:Schema(description = "cron 표현식", example = "0 0 * * * *")
    val cronExpression: String,
    @field:Min(value = 1, message = "startPage는 1 이상이어야 합니다")
    @field:Schema(description = "시작 페이지", example = "2")
    val startPage: Int = 1,
    @field:Min(value = 1, message = "endPage는 1 이상이어야 합니다")
    @field:Schema(description = "최초 등록 수집과 이후 실행에서 탐색할 마지막 페이지", example = "20")
    val endPage: Int = startPage,
    @field:Schema(description = "배치 활성화 여부", example = "true")
    val active: Boolean = true,
) {
    @get:JsonIgnore
    @get:AssertTrue(message = "endPage는 startPage보다 작을 수 없습니다")
    @get:Schema(hidden = true)
    val isPageRangeValid: Boolean
        get() = endPage >= startPage
}
