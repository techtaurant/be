package com.techtaurant.mainserver.user.infrastructure.`in`

import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.dto.CursorPageResponse
import com.techtaurant.mainserver.common.swagger.ApiErrorResponses
import com.techtaurant.mainserver.post.application.PostListReadService
import com.techtaurant.mainserver.post.dto.PostContentListItemResponse
import com.techtaurant.mainserver.post.entity.PostPeriod
import com.techtaurant.mainserver.post.entity.PostSortType
import com.techtaurant.mainserver.security.SecurityConstants
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("${SecurityConstants.OPEN_API_PREFIX}/v2/users")
@Validated
class UserPostOpenApiV2Controller(
    private val postListReadService: PostListReadService,
) : UserPostOpenApiV2ControllerDocs {
    @ApiErrorResponses(includeValidationError = true)
    @GetMapping("/{userId}/posts")
    override fun getPostContentsByUserId(
        @PathVariable userId: UUID,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
        @RequestParam(defaultValue = "ALL") period: PostPeriod,
        @RequestParam(defaultValue = "LATEST") sort: PostSortType,
        @RequestParam(required = false) categoryId: UUID?,
    ): ApiResponse<CursorPageResponse<PostContentListItemResponse>> {
        return ApiResponse.ok(
            postListReadService.getPostContents(
                cursor = cursor,
                size = size,
                period = period,
                sortType = sort,
                authorId = userId,
                categoryId = categoryId,
            ),
        )
    }
}
