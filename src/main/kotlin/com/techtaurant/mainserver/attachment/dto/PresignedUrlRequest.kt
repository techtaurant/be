package com.techtaurant.mainserver.attachment.dto

import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive

@Schema(description = "Presigned URL 발급 요청")
data class PresignedUrlRequest(
    @field:Schema(description = "업로드할 파일명", example = "photo.jpg")
    @field:NotBlank
    val fileName: String,
    @field:Schema(
        description = "파일 MIME 타입 (image/jpeg, image/png, image/gif, image/webp, image/avif만 허용)",
        example = "image/jpeg",
    )
    @field:NotBlank
    val contentType: String,
    @field:Schema(description = "파일 크기 (bytes, 최대 30MB)", example = "1048576")
    @field:Positive
    @field:Max(MAX_FILE_SIZE_BYTES)
    val fileSize: Long,
    @field:Schema(description = "첨부파일 연관 도메인 타입", example = "POST")
    val referenceType: AttachmentReferenceType,
) {
    companion object {
        /**
         * 첨부 업로드 허용 최대 크기.
         *
         * 이 값은 신고값의 상한일 뿐이므로 단독으로는 실제 업로드 크기를 보장하지 못한다.
         * 발급되는 presigned URL이 이 요청의 fileSize를 Content-Length로 서명에 포함하기 때문에,
         * 상한 검증과 서명이 함께 있을 때만 "실제 업로드 <= 상한"이 성립한다.
         */
        const val MAX_FILE_SIZE_BYTES: Long = 30L * 1024 * 1024
    }
}
