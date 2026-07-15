package com.techtaurant.mainserver.attachment.entity

import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.attachment.enums.AttachmentStatus
import com.techtaurant.mainserver.common.base.EntityBase
import java.util.UUID

/**
 * 첨부파일 엔티티
 *
 * referenceId + referenceType 으로 여러 도메인의 파일을 범용으로 관리한다.
 * 업로드 직후에는 TMP 상태로 S3 tmp/ 경로에 저장되며,
 * 게시물 publish 시 CONFIRMED 상태로 전환되고 posts/{referenceId}/ 경로로 이동된다.
 *
 * @property referenceId 연관 도메인 PK (TMP 상태에서는 null 가능)
 * @property referenceType 연관 도메인 타입 (POST 등)
 * @property objectKey S3 오브젝트 키 (tmp/{uuid}/{fileName} 또는 posts/{postId}/{uuid}/{fileName})
 * @property status 파일 상태 (TMP: 임시, CONFIRMED: 확정)
 * @property originalFileName 원본 파일명
 * @property contentType MIME 타입 (예: image/jpeg)
 * @property fileSize 파일 크기 (bytes)
 */
class Attachment(
    var referenceId: UUID? = null,
    var referenceType: AttachmentReferenceType,
    var objectKey: String,
    var status: AttachmentStatus,
    var originalFileName: String,
    var contentType: String,
    var fileSize: Long,
) : EntityBase()
