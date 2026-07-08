package com.techtaurant.mainserver.user.infrastructure.`in`

import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.dto.CursorPageResponse
import com.techtaurant.mainserver.common.swagger.ApiErrorResponses
import com.techtaurant.mainserver.post.application.PostDetailReadService
import com.techtaurant.mainserver.post.application.PostListReadService
import com.techtaurant.mainserver.post.dto.PostDetailResponse
import com.techtaurant.mainserver.post.dto.PostListItemResponse
import com.techtaurant.mainserver.post.entity.PostPeriod
import com.techtaurant.mainserver.post.entity.PostSortType
import com.techtaurant.mainserver.post.enums.PostStatus
import com.techtaurant.mainserver.security.SecurityConstants
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
@RequestMapping("${SecurityConstants.API_PREFIX}/users/me/posts")
@Validated
class UserPostController(
    private val postListReadService: PostListReadService,
    private val postDetailReadService: PostDetailReadService,
) : UserPostControllerDocs {
    @ApiErrorResponses(includeAuthenticationErrors = true, includeValidationError = true)
    @GetMapping
    override fun getMyPosts(
        @AuthenticationPrincipal userId: UUID,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
        @RequestParam(defaultValue = "ALL") period: PostPeriod,
        @RequestParam(defaultValue = "LATEST") sort: PostSortType,
        @RequestParam(required = false) categoryId: UUID?,
    ): ApiResponse<CursorPageResponse<PostListItemResponse>> {
        return ApiResponse.ok(
            postListReadService.getPosts(
                cursor = cursor,
                size = size,
                period = period,
                sortType = sort,
                currentUserId = userId,
                authorId = userId,
                categoryId = categoryId,
            ),
        )
    }

    @ApiErrorResponses(posts = [PostStatus.POST_NOT_FOUND], includeAuthenticationErrors = true)
    @GetMapping("/{postId}")
    override fun getMyPostDetail(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable postId: UUID,
    ): ApiResponse<PostDetailResponse> {
        return ApiResponse.ok(postDetailReadService.getMyPostDetail(postId, userId))
    }
}
