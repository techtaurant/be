package com.techtaurant.mainserver.attachment.infrastructure.`in`

import com.techtaurant.mainserver.attachment.dto.AttachmentPreviewUrlResponse
import com.techtaurant.mainserver.attachment.dto.AttachmentPreviewUrlsRequest
import com.techtaurant.mainserver.attachment.dto.PresignedUrlRequest
import com.techtaurant.mainserver.attachment.dto.PresignedUrlResponse
import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.swagger.ApiCommonBadRequestUnknownAndAuthenticationRequired
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import java.util.UUID
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse

@Tag(name = "Attachment", description = "첨부파일 API")
interface AttachmentControllerDocs {
    @Operation(
        summary = "임시 첨부파일 미리보기 URL 일괄 발급",
        description = "TMP 상태 첨부파일 여러 건을 미리보기 화면에서 표시할 수 있도록 GET Presigned URL을 한 번에 발급합니다.",
    )
    @SwaggerApiResponse(
        responseCode = "200",
        description = "임시 첨부파일 미리보기 URL 일괄 발급 성공",
    )
    @ApiCommonBadRequestUnknownAndAuthenticationRequired
    fun issueTmpPreviewUrls(
        userId: UUID,
        @Valid request: AttachmentPreviewUrlsRequest,
    ): ApiResponse<List<AttachmentPreviewUrlResponse>>

    @Operation(
        summary = "임시 첨부파일 미리보기 URL 발급",
        description = "TMP 상태 첨부파일을 미리보기 화면에서 표시할 수 있도록 GET Presigned URL을 발급합니다.",
    )
    @SwaggerApiResponse(
        responseCode = "200",
        description = "임시 첨부파일 미리보기 URL 발급 성공",
    )
    @ApiCommonBadRequestUnknownAndAuthenticationRequired
    fun issueTmpPreviewUrl(
        userId: UUID,
        @Parameter(description = "미리보기할 TMP Attachment ID") attachmentId: UUID,
    ): ApiResponse<AttachmentPreviewUrlResponse>

    @Operation(
        summary = "Presigned URL 발급",
        description =
            "S3 직접 업로드를 위한 PUT Presigned URL을 발급합니다. " +
                "업로드 후에는 응답의 attachmentId를 게시물 content에 삽입하세요. " +
                "objectKey는 업로드 대상 S3 경로일 뿐이며, 확정 시점에 경로가 바뀌므로 content에 넣으면 안 됩니다.",
    )
    @SwaggerApiResponse(
        responseCode = "200",
        description = "Presigned URL 발급 성공",
    )
    @ApiCommonBadRequestUnknownAndAuthenticationRequired
    fun issuePresignedUploadUrl(
        userId: UUID,
        @Valid request: PresignedUrlRequest,
    ): ApiResponse<PresignedUrlResponse>
}
