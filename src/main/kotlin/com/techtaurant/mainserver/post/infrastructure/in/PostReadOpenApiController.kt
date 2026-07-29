package com.techtaurant.mainserver.post.infrastructure.`in`

import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.dto.CursorPageResponse
import com.techtaurant.mainserver.common.swagger.ApiErrorResponses
import com.techtaurant.mainserver.common.util.HttpRequestUtils
import com.techtaurant.mainserver.post.application.PostDetailReadService
import com.techtaurant.mainserver.post.application.PostListReadService
import com.techtaurant.mainserver.post.dto.PostDetailResponse
import com.techtaurant.mainserver.post.dto.PostListItemResponse
import com.techtaurant.mainserver.post.entity.PostPeriod
import com.techtaurant.mainserver.post.entity.PostSortType
import com.techtaurant.mainserver.post.enums.PostStatus
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/open-api/posts")
@Validated
class PostReadOpenApiController(
    private val postListReadService: PostListReadService,
    private val postDetailReadService: PostDetailReadService,
) : PostReadOpenApiControllerDocs {
    @ApiErrorResponses(includeValidationError = true)
    @GetMapping
    override fun getPosts(
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
        @RequestParam(defaultValue = "ALL") period: PostPeriod,
        @RequestParam(defaultValue = "LATEST") sort: PostSortType,
        @RequestParam(required = false) authorId: UUID?,
        @RequestParam(required = false) categoryId: UUID?,
        @RequestParam(required = false) tagIds: List<UUID>?,
        @AuthenticationPrincipal currentUserId: UUID?,
    ): ApiResponse<CursorPageResponse<PostListItemResponse>> {
        return ApiResponse.ok(
            postListReadService.getPosts(
                cursor = cursor,
                size = size,
                period = period,
                sortType = sort,
                currentUserId = currentUserId,
                authorId = authorId,
                categoryId = categoryId,
                tagIds = tagIds,
            ),
        )
    }

    @ApiErrorResponses(posts = [PostStatus.POST_NOT_FOUND])
    @GetMapping("/{postId}")
    override fun getPostDetail(
        @PathVariable postId: UUID,
        request: HttpServletRequest,
        @AuthenticationPrincipal userId: UUID?,
    ): ApiResponse<PostDetailResponse> {
        val ipAddress = HttpRequestUtils.extractIpAddress(request)
        val userAgent = request.getHeader("User-Agent")

        return ApiResponse.ok(
            postDetailReadService.getPostDetail(
                postId = postId,
                userId = userId,
                ipAddress = ipAddress,
                userAgent = userAgent,
            ),
        )
    }
}
