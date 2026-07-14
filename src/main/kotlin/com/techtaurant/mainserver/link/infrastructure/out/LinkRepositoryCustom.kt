package com.techtaurant.mainserver.link.infrastructure.out

import com.techtaurant.mainserver.link.dto.LinkCursorV1
import com.techtaurant.mainserver.link.entity.Link
import com.techtaurant.mainserver.link.enums.LinkPeriod
import com.techtaurant.mainserver.link.enums.LinkSortType
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * 공개 링크 동적 정렬/페이지네이션을 위한 커스텀 Repository
 */
interface LinkRepositoryCustom {
    fun findById(id: UUID): Optional<Link>

    fun existsById(id: UUID): Boolean

    /**
     * 정렬 타입과 기간 필터를 적용해 공개 링크 ID를 정렬 순서대로 조회합니다.
     *
     * 엔티티 fetch는 호출 측에서 [LinkRepository.findAllByIdInWithTags]로 수행하며,
     * 이 메서드는 정렬값을 포함한 ID 목록만 반환합니다.
     *
     * @param cursor 커서 (null이면 첫 페이지)
     * @param limit 조회 개수 (hasNext 판별을 위해 보통 size + 1을 전달)
     * @param sortType 정렬 타입
     * @param period 기간 필터 (PUBLISHED는 생성일, LIKE/SAVE는 일별 집계 윈도우)
     * @param sourceCompanyUserId 출처 회사 사용자 ID 필터 (null이면 전체)
     * @param tag 태그명 필터 (null이면 전체)
     * @return 정렬 순서를 유지한 ID + 정렬값 목록
     */
    fun findPublicLinkIds(
        cursor: LinkCursorV1?,
        limit: Int,
        sortType: LinkSortType,
        period: LinkPeriod,
        sourceCompanyUserId: UUID?,
        tag: String?,
    ): List<RankedLinkId>

    fun findByUrl(url: String): Link?

    fun findByIdWithTags(linkId: UUID): Link?

    fun findAllWithTags(): List<Link>

    fun findAllByConnectedUserIdWithTags(companyUserId: UUID): List<Link>

    fun findFirstPageIds(
        sourceCompanyUserId: UUID?,
        tag: String?,
        pageable: Pageable,
    ): List<UUID>

    fun findNextPageIds(
        sourceCompanyUserId: UUID?,
        tag: String?,
        cursorCreatedAt: Instant,
        cursorId: UUID,
        pageable: Pageable,
    ): List<UUID>

    fun findAllByIdInWithTags(linkIds: List<UUID>): List<Link>
}

/**
 * 정렬 순서대로 조회된 링크 ID와 실제 정렬값
 *
 * @property linkId 링크 ID
 * @property sortValue LIKE/SAVE 정렬 시 기간 집계 합 (PUBLISHED는 0)
 */
data class RankedLinkId(
    val linkId: UUID,
    val sortValue: Long,
)
