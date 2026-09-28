package com.techtaurant.mainserver.post.dto

import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.entity.PostSortType
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * 게시물 목록 커서
 *
 * 정렬 기준에 따라 다른 커서 값을 사용
 * - LATEST: createdAt, id
 * - UPDATED: updatedAt, id
 * - VIEW/LIKE/COMMENT: sortValue(해당 count), createdAt, id
 *
 * @property sortValue 정렬 기준 값 (LATEST/UPDATED는 기준 시각의 epoch milliseconds, 그 외는 조회수/좋아요수/댓글수)
 * @property createdAt UPDATED 정렬 시 updatedAt, 그 외 정렬 시 createdAt을 담는 보조 정렬 시간 필드
 * @property id 게시물 ID
 * @property sortType 정렬 타입
 */
data class PostCursor(
    val sortValue: Long,
    val createdAt: Instant,
    val id: UUID,
    val sortType: PostSortType,
) {
    /**
     * 커서를 Base64 인코딩된 문자열로 변환
     */
    fun encode(): String {
        val raw = "${sortType.name}|$sortValue|$createdAt|$id"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())
    }

    companion object {
        /**
         * Base64 인코딩된 커서 문자열을 PostCursor로 디코딩
         *
         * @param cursor Base64 인코딩된 커서 문자열
         * @return 디코딩된 PostCursor, 실패 시 null
         */
        fun decode(cursor: String): PostCursor? {
            return try {
                val decoded = String(Base64.getUrlDecoder().decode(cursor))
                decodePipeDelimited(decoded) ?: decodeLegacyColonDelimited(decoded)
            } catch (e: Exception) {
                null
            }
        }

        private fun decodePipeDelimited(decoded: String): PostCursor? {
            val parts = decoded.split("|")
            if (parts.size != 4) return null

            val sortType = PostSortType.fromString(parts[0])
            val sortValue = parts[1].toLongOrNull() ?: return null
            val timestamp = Instant.parse(parts[2])
            val uuid = UUID.fromString(parts[3])

            return PostCursor(sortValue, timestamp, uuid, sortType)
        }

        private fun decodeLegacyColonDelimited(decoded: String): PostCursor? {
            val parts = decoded.split(":")
            if (parts.size != 4) return null

            val sortType = PostSortType.fromString(parts[0])
            val sortValue = parts[1].toLongOrNull() ?: return null
            val timestamp = parts[2].toLongOrNull() ?: return null
            val uuid = UUID.fromString(parts[3])

            return PostCursor(sortValue, Instant.ofEpochMilli(timestamp), uuid, sortType)
        }

        /**
         * 쿼리에서 실제 정렬에 사용한 값으로 커서를 생성합니다.
         *
         * @param post 게시물 엔티티
         * @param sortType 정렬 타입
         * @param sortValue 정렬에 사용된 값
         */
        fun from(
            post: Post,
            sortType: PostSortType,
            sortValue: Long,
        ): PostCursor {
            val cursorDate = if (sortType == PostSortType.UPDATED) post.updatedAt else post.createdAt
            return PostCursor(sortValue, cursorDate, post.id!!, sortType)
        }
    }
}
