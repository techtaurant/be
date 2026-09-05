package com.techtaurant.mainserver.post.dto

import com.techtaurant.mainserver.common.enums.LikeStatus
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.user.entity.User
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/**
 * 게시물 상세 조회 응답 DTO
 *
 * @property id 게시물 ID
 * @property title 게시물 제목
 * @property content 게시물 본문
 * @property author 작성자 정보
 * @property category 카테고리 정보
 * @property tags 태그 목록
 * @property viewCount 조회수
 * @property likeCount 좋아요수
 * @property commentCount 댓글수
 * @property likeStatus 현재 사용자의 좋아요 상태
 * @property status 게시물 상태 (DRAFT: 임시저장, PUBLISHED: 발행, PRIVATE: 비공개)
 * @property isRead 현재 사용자가 읽음 표시한 게시물인지 여부 (비회원은 항상 false)
 * @property thumbnailAttachmentId 대표 썸네일로 지정된 첨부 ID
 * @property attachmentPresignedUrls attachmentId와 presigned URL 매핑 목록
 * @property createdAt 작성일
 * @property updatedAt 수정일
 */
@Schema(description = "게시물 상세 응답")
data class PostDetailResponse(
    @field:Schema(description = "게시물 ID")
    val id: UUID,
    @field:Schema(description = "게시물 제목")
    val title: String,
    @field:Schema(description = "게시물 본문")
    val content: String,
    @field:Schema(description = "작성자 정보")
    val author: AuthorResponse,
    @field:Schema(description = "카테고리 정보")
    val category: CategoryResponse?,
    @field:Schema(description = "태그 목록")
    val tags: List<PostListTagResponse>,
    @field:Schema(description = "조회수")
    val viewCount: Long,
    @field:Schema(description = "좋아요수")
    val likeCount: Long,
    @field:Schema(description = "댓글수")
    val commentCount: Long,
    @field:Schema(description = "현재 사용자의 좋아요 상태")
    val likeStatus: LikeStatus,
    @field:Schema(description = "게시물 상태 (DRAFT: 임시저장, PUBLISHED: 발행, PRIVATE: 비공개)")
    val status: PostStatusEnum,
    @field:Schema(description = "현재 사용자가 읽음 표시한 게시물인지 여부")
    val isRead: Boolean,
    @field:Schema(
        description =
            "대표 썸네일로 지정된 첨부 ID (지정하지 않았으면 null). " +
                "확정된 첨부는 attachmentPresignedUrls에서 URL을 찾을 수 있고, " +
                "임시저장 게시물의 미확정 첨부는 본문 이미지와 동일하게 TMP 미리보기 URL 발급 API를 사용한다",
        example = "550e8400-e29b-41d4-a716-446655440000",
    )
    val thumbnailAttachmentId: UUID?,
    @field:Schema(description = "attachmentId와 presigned URL 매핑 목록")
    val attachmentPresignedUrls: List<PostDetailAttachmentPresignedUrlResponse>,
    @field:Schema(description = "작성일")
    val createdAt: Instant,
    @field:Schema(description = "수정일")
    val updatedAt: Instant,
) {
    companion object {
        /**
         * Post 엔티티를 PostDetailResponse로 변환합니다.
         *
         * @param post 게시물 엔티티
         * @param likeStatus 현재 사용자의 좋아요 상태
         * @param isRead 현재 사용자가 읽음 표시한 게시물인지 여부
         * @param attachmentPresignedUrls attachmentId와 presigned URL 매핑 목록
         * @return 게시물 상세 응답 DTO
         */
        fun from(
            post: Post,
            likeStatus: LikeStatus,
            isRead: Boolean,
            authorProfileImageUrl: String,
            content: String = post.content,
            attachmentPresignedUrls: List<PostDetailAttachmentPresignedUrlResponse> = emptyList(),
        ): PostDetailResponse =
            PostDetailResponse(
                id = post.id!!,
                title = post.title,
                content = content,
                author = AuthorResponse.from(post.author, authorProfileImageUrl),
                category = post.category?.let { CategoryResponse.from(it) },
                tags = post.tags.map { PostListTagResponse.from(it) },
                viewCount = post.viewCount,
                likeCount = post.likeCount,
                commentCount = post.commentCount,
                likeStatus = likeStatus,
                status = post.status,
                isRead = isRead,
                thumbnailAttachmentId = post.thumbnailImage,
                attachmentPresignedUrls = attachmentPresignedUrls,
                createdAt = post.createdAt,
                updatedAt = post.updatedAt,
            )
    }
}

@Schema(description = "게시물 본문 attachment presigned URL 매핑")
data class PostDetailAttachmentPresignedUrlResponse(
    @field:Schema(description = "첨부파일 ID", example = "550e8400-e29b-41d4-a716-446655440000")
    val attachmentId: UUID,
    @field:Schema(description = "첨부파일 다운로드용 presigned URL", example = "https://techtaurant-media.s3.ap-northeast-2.amazonaws.com/post/...")
    val presignedUrl: String,
) {
    companion object {
        fun from(
            attachmentId: UUID,
            presignedUrl: String,
        ): PostDetailAttachmentPresignedUrlResponse =
            PostDetailAttachmentPresignedUrlResponse(
                attachmentId = attachmentId,
                presignedUrl = presignedUrl,
            )
    }
}

/**
 * 작성자 정보 응답 DTO
 *
 * @property id 작성자 ID
 * @property name 작성자 이름
 * @property profileImageUrl 프로필 이미지 URL
 */
@Schema(description = "작성자 정보")
data class AuthorResponse(
    @field:Schema(description = "작성자 ID")
    val id: UUID,
    @field:Schema(description = "작성자 이름")
    val name: String,
    @field:Schema(description = "프로필 이미지 URL")
    val profileImageUrl: String,
) {
    companion object {
        fun from(
            user: User,
            profileImageUrl: String,
        ): AuthorResponse =
            AuthorResponse(
                id = user.id!!,
                name = user.name,
                profileImageUrl = profileImageUrl,
            )
    }
}
