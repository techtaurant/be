package com.techtaurant.mainserver.link.dto

import com.techtaurant.mainserver.link.entity.Link
import com.techtaurant.mainserver.link.enums.LinkSortType
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * 공개 링크 목록 커서
 *
 * 정렬 타입을 커서에 명시적으로 담아, 커서가 어떤 정렬에 속하는지 한눈에 알 수 있도록 합니다.
 * 정렬 타입별로 사용하는 키셋 값이 다릅니다.
 * - PUBLISHED: sortInstant = 링크 생성일(createdAt), id (sortValue 미사용)
 * - LIKE/SAVE: sortValue = 기간 내 일별 집계 합, sortInstant = createdAt, id
 *
 * 인코딩 형식: Base64Url("{sortType}|{sortValue}|{sortInstant}|{id}")
 *
 * @property sortType 정렬 타입
 * @property sortValue LIKE/SAVE의 기간 집계 합 (PUBLISHED는 미사용, 0)
 * @property sortInstant 링크 생성일
 * @property id 링크 ID
 */
data class LinkCursor(
    val sortType: LinkSortType,
    val sortValue: Long,
    val sortInstant: Instant,
    val id: UUID,
) {
    fun encode(): String {
        val raw = "${sortType.name}$CURSOR_DELIMITER$sortValue$CURSOR_DELIMITER$sortInstant$CURSOR_DELIMITER$id"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())
    }

    companion object {
        private const val CURSOR_DELIMITER = "|"

        fun decode(cursor: String): LinkCursor? =
            runCatching {
                val decoded = String(Base64.getUrlDecoder().decode(cursor))
                val parts = decoded.split(CURSOR_DELIMITER)
                require(parts.size == 4)
                LinkCursor(
                    sortType = LinkSortType.fromString(parts[0]),
                    sortValue = parts[1].toLong(),
                    sortInstant = parseInstant(parts[2]),
                    id = UUID.fromString(parts[3]),
                )
            }.getOrNull()

        private fun parseInstant(value: String): Instant = value.toLongOrNull()?.let(Instant::ofEpochMilli) ?: Instant.parse(value)

        /**
         * 쿼리에서 실제 정렬에 사용한 값으로 다음 페이지 커서를 생성합니다.
         *
         * @param link 마지막 링크 엔티티
         * @param sortType 정렬 타입
         * @param sortValue LIKE/SAVE 정렬에 사용된 기간 집계 합 (PUBLISHED는 무시됨)
         */
        fun from(
            link: Link,
            sortType: LinkSortType,
            sortValue: Long,
        ): LinkCursor {
            return LinkCursor(sortType, sortValue, link.createdAt, link.id!!)
        }
    }
}
