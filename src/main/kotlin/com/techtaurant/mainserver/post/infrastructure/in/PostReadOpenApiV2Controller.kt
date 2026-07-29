package com.techtaurant.mainserver.post.infrastructure.`in`

import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.dto.CursorPageResponse
import com.techtaurant.mainserver.common.swagger.ApiErrorResponses
import com.techtaurant.mainserver.post.application.PostDetailReadService
import com.techtaurant.mainserver.post.application.PostListReadService
import com.techtaurant.mainserver.post.dto.PostContentDetailResponse
import com.techtaurant.mainserver.post.dto.PostContentListItemResponse
import com.techtaurant.mainserver.post.entity.PostPeriod
import com.techtaurant.mainserver.post.entity.PostSortType
import com.techtaurant.mainserver.post.enums.PostStatus
import com.techtaurant.mainserver.security.SecurityConstants
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("${SecurityConstants.OPEN_API_PREFIX}/v2/posts")
@Validated
class PostReadOpenApiV2Controller(
    private val postListReadService: PostListReadService,
    private val postDetailReadService: PostDetailReadService,
) : PostReadOpenApiV2ControllerDocs {
    @ApiErrorResponses(includeValidationError = true)
    @GetMapping
    override fun getPostContents(
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
        @RequestParam(defaultValue = "ALL") period: PostPeriod,
        @RequestParam(defaultValue = "LATEST") sort: PostSortType,
        @RequestParam(required = false) authorId: UUID?,
        @RequestParam(required = false) categoryId: UUID?,
        @RequestParam(required = false) tagIds: List<UUID>?,
        @RequestParam(required = false)
        @Size(min = 2, max = 100)
        @Pattern(regexp = "(?s).*\\S.*")
        keyword: String?,
    ): ApiResponse<CursorPageResponse<PostContentListItemResponse>> {
        return ApiResponse.ok(
            postListReadService.getPostContents(
                cursor = cursor,
                size = size,
                period = period,
                sortType = sort,
                authorId = authorId,
                categoryId = categoryId,
                tagIds = tagIds,
                keyword = keyword,
            ),
        )
    }

    @ApiErrorResponses(posts = [PostStatus.POST_NOT_FOUND])
    @GetMapping("/{postId}")
    override fun getPostContentDetail(
        @PathVariable postId: UUID,
    ): ApiResponse<PostContentDetailResponse> {
        return ApiResponse.ok(postDetailReadService.getPublishedPostContentDetail(postId))
    }
}
