package com.techtaurant.mainserver.link.infrastructure.`in`

import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.swagger.ApiErrorResponses
import com.techtaurant.mainserver.link.application.LinkLikeLogService
import com.techtaurant.mainserver.link.application.LinkReadLogService
import com.techtaurant.mainserver.link.application.LinkSaveService
import com.techtaurant.mainserver.link.dto.RecordLinkLikeRequest
import com.techtaurant.mainserver.link.dto.RecordLinkReadRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@Validated
@RequestMapping("/api")
class LinkController(
    private val linkSaveService: LinkSaveService,
    private val linkReadLogService: LinkReadLogService,
    private val linkLikeLogService: LinkLikeLogService,
) : LinkControllerDocs {
    @ApiErrorResponses(includeAuthenticationErrors = true)
    @PostMapping("/links/{linkId}/save")
    @ResponseStatus(HttpStatus.CREATED)
    override fun saveLink(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable linkId: UUID,
    ): ApiResponse<Unit> {
        linkSaveService.save(linkId, userId)
        return ApiResponse.created(Unit)
    }

    @ApiErrorResponses(includeAuthenticationErrors = true)
    @DeleteMapping("/links/{linkId}/save")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    override fun unsaveLink(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable linkId: UUID,
    ) {
        linkSaveService.unsave(linkId, userId)
    }

    @ApiErrorResponses(includeAuthenticationErrors = true, includeValidationError = true)
    @PostMapping("/links/{linkId}/read-logs")
    override fun toggleReadStatus(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable linkId: UUID,
        @Valid @RequestBody request: RecordLinkReadRequest,
    ): ApiResponse<Unit> {
        linkReadLogService.toggleReadStatus(linkId, userId, request.isRead)
        return ApiResponse.ok(Unit)
    }

    @ApiErrorResponses(includeAuthenticationErrors = true, includeValidationError = true)
    @PostMapping("/links/{linkId}/like")
    override fun recordLike(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable linkId: UUID,
        @Valid @RequestBody request: RecordLinkLikeRequest,
    ): ApiResponse<Unit> {
        linkLikeLogService.recordLike(
            linkId = linkId,
            userId = userId,
            likeStatus = request.likeStatus,
        )
        return ApiResponse.ok(Unit)
    }
}
