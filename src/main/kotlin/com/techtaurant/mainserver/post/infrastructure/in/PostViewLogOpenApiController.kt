package com.techtaurant.mainserver.post.infrastructure.`in`

import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.swagger.ApiErrorResponses
import com.techtaurant.mainserver.common.util.HttpRequestUtils
import com.techtaurant.mainserver.post.application.PostDetailReadService
import com.techtaurant.mainserver.post.application.PostViewLogService
import com.techtaurant.mainserver.post.enums.PostStatus
import com.techtaurant.mainserver.security.SecurityConstants
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("${SecurityConstants.OPEN_API_PREFIX}/posts")
@Validated
class PostViewLogOpenApiController(
    private val postDetailReadService: PostDetailReadService,
    private val postViewLogService: PostViewLogService,
) : PostViewLogOpenApiControllerDocs {
    @ApiErrorResponses(posts = [PostStatus.POST_NOT_FOUND])
    @PostMapping("/{postId}/view-logs")
    override fun recordPostView(
        @PathVariable postId: UUID,
        request: HttpServletRequest,
        @AuthenticationPrincipal userId: UUID?,
    ): ApiResponse<Unit> {
        postDetailReadService.getAccessiblePostDetailById(postId, userId)
        postViewLogService.recordView(
            postId = postId,
            userId = userId,
            ipAddress = HttpRequestUtils.extractIpAddress(request),
            userAgent = request.getHeader("User-Agent"),
        )

        return ApiResponse.ok(Unit)
    }
}
