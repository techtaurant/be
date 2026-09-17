package com.techtaurant.mainserver.link.infrastructure.`in`

import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.dto.CursorPageResponse
import com.techtaurant.mainserver.common.swagger.ApiErrorResponses
import com.techtaurant.mainserver.link.application.LinkReadService
import com.techtaurant.mainserver.link.dto.LinkContentDetailResponse
import com.techtaurant.mainserver.link.dto.LinkContentListItemResponse
import com.techtaurant.mainserver.link.enums.LinkPeriod
import com.techtaurant.mainserver.link.enums.LinkSortType
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
@RequestMapping("${SecurityConstants.OPEN_API_PREFIX}/links")
@Validated
class LinkReadOpenApiController(
    private val linkReadService: LinkReadService,
) : LinkReadOpenApiControllerDocs {
    @ApiErrorResponses(includeValidationError = true)
    @GetMapping
    override fun getLinkContents(
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
        @RequestParam(defaultValue = "PUBLISHED") sort: LinkSortType,
        @RequestParam(defaultValue = "ALL") period: LinkPeriod,
        @RequestParam(required = false) sourceCompanyUserId: UUID?,
        @RequestParam(required = false) tag: String?,
    ): ApiResponse<CursorPageResponse<LinkContentListItemResponse>> {
        return ApiResponse.ok(
            linkReadService.getPublicLinkContents(
                cursor = cursor,
                size = size,
                sortType = sort,
                period = period,
                sourceCompanyUserId = sourceCompanyUserId,
                tag = tag,
            ),
        )
    }

    @GetMapping("/{linkId}")
    override fun getLinkContentDetail(
        @PathVariable linkId: UUID,
    ): ApiResponse<LinkContentDetailResponse> {
        return ApiResponse.ok(linkReadService.getPublicLinkContentDetail(linkId))
    }
}
