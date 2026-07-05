package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.common.dto.CursorPageResponse
import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.link.dto.LinkContentDetailResponse
import com.techtaurant.mainserver.link.dto.LinkContentListItemResponse
import com.techtaurant.mainserver.link.dto.LinkCursor
import com.techtaurant.mainserver.link.dto.LinkCursorV1
import com.techtaurant.mainserver.link.entity.Link
import com.techtaurant.mainserver.link.enums.LinkPeriod
import com.techtaurant.mainserver.link.enums.LinkSortType
import com.techtaurant.mainserver.link.enums.LinkStatus
import com.techtaurant.mainserver.link.infrastructure.out.LinkRepository
import com.techtaurant.mainserver.link.infrastructure.out.UserLinkRepository
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.enums.UserStatus
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class LinkReadService(
    private val linkRepository: LinkRepository,
    private val userLinkRepository: UserLinkRepository,
    private val userRepository: UserRepository,
) {
    fun getPublicLinkContents(
        cursor: String?,
        size: Int,
        sourceCompanyUserId: UUID?,
        tag: String?,
    ): CursorPageResponse<LinkContentListItemResponse> {
        val linkPage =
            getLinkPage(
                cursor = cursor,
                size = size,
                sourceCompanyUserId = sourceCompanyUserId,
                tag = tag,
            )
        val sourceCompanyUserIdByLinkId = findSourceCompanyUserIdByLinkId(linkPage.content)

        return CursorPageResponse(
            content =
                linkPage.content.map { link ->
                    LinkContentListItemResponse.from(
                        link = link,
                        sourceCompanyUserId = sourceCompanyUserIdByLinkId[link.id],
                    )
                },
            nextCursor = linkPage.nextCursor,
            hasNext = linkPage.hasNext,
            size = linkPage.size,
        )
    }

    /**
     * 공개 링크 정적 콘텐츠 목록을 명시적 정렬/기간과 함께 커서 기반으로 조회합니다 (v1).
     *
     * - PUBLISHED: 기간(period) 내 링크 생성일 최신순
     * - LIKE/SAVE: 기간(period) 윈도우 내 일별 좋아요/저장 집계 합 기준 (period=ALL이면 전체 누적)
     *
     * 커서는 정렬 타입을 포함하며, 요청 sort와 커서의 정렬 타입이 다르면 INVALID_LINK_CURSOR를 반환합니다.
     */
    fun getPublicLinkContentsV1(
        cursor: String?,
        size: Int,
        sortType: LinkSortType,
        period: LinkPeriod,
        sourceCompanyUserId: UUID?,
        tag: String?,
    ): CursorPageResponse<LinkContentListItemResponse> {
        val linkPage =
            getLinkPageV1(
                cursor = cursor,
                size = size,
                sortType = sortType,
                period = period,
                sourceCompanyUserId = sourceCompanyUserId,
                tag = tag,
            )
        val sourceCompanyUserIdByLinkId = findSourceCompanyUserIdByLinkId(linkPage.content)

        return CursorPageResponse(
            content =
                linkPage.content.map { link ->
                    LinkContentListItemResponse.from(
                        link = link,
                        sourceCompanyUserId = sourceCompanyUserIdByLinkId[link.id],
                    )
                },
            nextCursor = linkPage.nextCursor,
            hasNext = linkPage.hasNext,
            size = linkPage.size,
        )
    }

    fun getPublicCompanyLinkContents(
        companyUserId: UUID,
        cursor: String?,
        size: Int,
    ): CursorPageResponse<LinkContentListItemResponse> {
        validateCompany(companyUserId)

        return getPublicLinkContents(
            cursor = cursor,
            size = size,
            sourceCompanyUserId = companyUserId,
            tag = null,
        )
    }

    fun getPublicLinkContentDetail(linkId: UUID): LinkContentDetailResponse {
        val link =
            linkRepository.findByIdWithTags(linkId)
                ?: throw ApiException(LinkStatus.LINK_NOT_FOUND)

        return LinkContentDetailResponse.from(
            link = link,
            sourceCompanyUserId = findSourceCompanyUserIdByLinkId(listOf(link))[link.id],
        )
    }

    private fun getLinkPage(
        cursor: String?,
        size: Int,
        sourceCompanyUserId: UUID?,
        tag: String?,
    ): CursorPageResponse<Link> {
        val linkCursor = cursor?.let { LinkCursor.decode(it) }

        if (cursor != null && linkCursor == null) {
            throw ApiException(LinkStatus.INVALID_LINK_CURSOR)
        }

        val normalizedTag = tag?.takeIf { it.isNotBlank() }
        val pageable = PageRequest.of(0, size + 1)
        val linkIds =
            when {
                linkCursor == null ->
                    linkRepository.findFirstPageIds(
                        sourceCompanyUserId = sourceCompanyUserId,
                        tag = normalizedTag,
                        pageable = pageable,
                    )

                else ->
                    linkRepository.findNextPageIds(
                        sourceCompanyUserId = sourceCompanyUserId,
                        tag = normalizedTag,
                        cursorCreatedAt = linkCursor.createdAt,
                        cursorId = linkCursor.id,
                        pageable = pageable,
                    )
            }
        val hasNext = linkIds.size > size
        val contentLinkIds = linkIds.take(size)
        val linksById =
            if (contentLinkIds.isEmpty()) {
                emptyMap()
            } else {
                linkRepository.findAllByIdInWithTags(contentLinkIds).associateBy { it.id }
            }
        val contentLinks = contentLinkIds.mapNotNull(linksById::get)

        val nextCursor =
            if (hasNext && contentLinks.isNotEmpty()) {
                LinkCursor.from(contentLinks.last()).encode()
            } else {
                null
            }

        return CursorPageResponse(
            content = contentLinks,
            nextCursor = nextCursor,
            hasNext = hasNext,
            size = contentLinks.size,
        )
    }

    private fun getLinkPageV1(
        cursor: String?,
        size: Int,
        sortType: LinkSortType,
        period: LinkPeriod,
        sourceCompanyUserId: UUID?,
        tag: String?,
    ): CursorPageResponse<Link> {
        val linkCursor = cursor?.let { LinkCursorV1.decode(it) }

        if (cursor != null && !isValidCursor(linkCursor, sortType)) {
            throw ApiException(LinkStatus.INVALID_LINK_CURSOR)
        }

        val normalizedTag = tag?.takeIf { it.isNotBlank() }
        val rankedLinkIds =
            linkRepository.findPublicLinkIds(
                cursor = linkCursor,
                limit = size + 1,
                sortType = sortType,
                period = period,
                sourceCompanyUserId = sourceCompanyUserId,
                tag = normalizedTag,
            )
        val hasNext = rankedLinkIds.size > size
        val pageRankedLinkIds = rankedLinkIds.take(size)
        val linksById =
            if (pageRankedLinkIds.isEmpty()) {
                emptyMap()
            } else {
                linkRepository.findAllByIdInWithTags(pageRankedLinkIds.map { it.linkId }).associateBy { it.id }
            }
        val contentLinks = pageRankedLinkIds.mapNotNull { linksById[it.linkId] }

        val lastRankedLinkId = pageRankedLinkIds.lastOrNull()
        val nextCursor =
            if (hasNext && contentLinks.isNotEmpty() && lastRankedLinkId != null) {
                LinkCursorV1.from(
                    link = linksById.getValue(lastRankedLinkId.linkId),
                    sortType = sortType,
                    sortValue = lastRankedLinkId.sortValue,
                ).encode()
            } else {
                null
            }

        return CursorPageResponse(
            content = contentLinks,
            nextCursor = nextCursor,
            hasNext = hasNext,
            size = contentLinks.size,
        )
    }

    private fun isValidCursor(
        linkCursor: LinkCursorV1?,
        sortType: LinkSortType,
    ): Boolean = linkCursor != null && linkCursor.sortType == sortType

    private fun validateCompany(companyUserId: UUID) {
        val company =
            userRepository.findById(companyUserId).orElseThrow {
                ApiException(UserStatus.COMPANY_NOT_FOUND)
            }

        if (company.role != UserRole.COMPANY) {
            throw ApiException(UserStatus.COMPANY_NOT_FOUND)
        }
    }

    private fun findSourceCompanyUserIdByLinkId(links: List<Link>): Map<UUID, UUID> {
        val linkIds = links.mapNotNull { it.id }
        if (linkIds.isEmpty()) {
            return emptyMap()
        }

        return linkIds.associateWith { linkId ->
            userLinkRepository.findFirstSourceByLinkId(linkId, PageRequest.of(0, 1)).firstOrNull()?.user?.id
                ?: throw ApiException(LinkStatus.LINK_NOT_FOUND)
        }
    }
}
