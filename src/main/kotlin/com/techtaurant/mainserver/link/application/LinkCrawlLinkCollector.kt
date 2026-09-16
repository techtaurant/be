package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.common.status.DefaultStatus
import com.techtaurant.mainserver.link.entity.Link
import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import com.techtaurant.mainserver.link.entity.LinkCrawlFailedJob
import com.techtaurant.mainserver.link.entity.UserLink
import com.techtaurant.mainserver.link.infrastructure.out.LinkCrawlFailedJobRepository
import com.techtaurant.mainserver.link.infrastructure.out.LinkRepository
import com.techtaurant.mainserver.link.infrastructure.out.UserLinkRepository
import com.techtaurant.mainserver.post.application.TagWriteService
import com.techtaurant.mainserver.post.entity.Tag
import com.techtaurant.mainserver.user.entity.User
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class LinkCrawlLinkCollector(
    private val linkRepository: LinkRepository,
    private val userLinkRepository: UserLinkRepository,
    private val tagWriteService: TagWriteService,
    private val linkCrawlFailedJobRepository: LinkCrawlFailedJobRepository,
) {
    internal fun tagResolverFor(batch: LinkCrawlBatch): LinkTagResolver {
        return LinkTagResolver(resolveLinkTagNames(batch.tagNames), tagWriteService::resolveTags)
    }

    internal fun collect(
        snapshot: LinkSnapshot,
        batch: LinkCrawlBatch,
        tagResolver: LinkTagResolver,
    ): LinkCrawlLinkCollectResult {
        val collectResult = saveOrRefreshLink(snapshot, batch, tagResolver)
        resolveFailedArticleUrl(batch, snapshot.url)
        return collectResult
    }

    private fun saveOrRefreshLink(
        snapshot: LinkSnapshot,
        batch: LinkCrawlBatch,
        tagResolver: LinkTagResolver,
    ): LinkCrawlLinkCollectResult {
        val existingLink = linkRepository.findByUrl(snapshot.url)
        if (existingLink == null) {
            val savedLink = saveNewLink(snapshot, tagResolver.resolve())
            connectUserToLink(batch.companyUser, savedLink)
            return LinkCrawlLinkCollectResult.CREATED_NEW_LINK
        }

        refreshExistingLink(existingLink, snapshot)
        val isConnected = connectUserToLink(batch.companyUser, existingLink)
        return if (isConnected) {
            LinkCrawlLinkCollectResult.CONNECTED_EXISTING_LINK
        } else {
            LinkCrawlLinkCollectResult.UPDATED_EXISTING_LINK
        }
    }

    /**
     * 수집에 성공했다는 것은 그 URL이 더 이상 실패 상태가 아니라는 뜻이므로 남아 있던 실패 기록을 닫는다.
     * 정기 실행, 재시도, 관리자 수동 등록이 모두 이 경로를 지나므로 해소 규칙이 경로별로 갈라지지 않는다.
     */
    private fun resolveFailedArticleUrl(
        batch: LinkCrawlBatch,
        url: String,
    ) {
        val batchId = batch.id ?: return
        val failedJob =
            linkCrawlFailedJobRepository.findByBatchIdAndArticleUrl(batchId, LinkCrawlFailedJob.truncateUrl(url)) ?: return
        if (failedJob.resolvedAt != null) {
            return
        }

        failedJob.resolvedAt = Instant.now()
        linkCrawlFailedJobRepository.save(failedJob)
    }

    private fun saveNewLink(
        snapshot: LinkSnapshot,
        tags: Set<Tag>,
    ): Link {
        return linkRepository.save(
            Link(
                title = snapshot.title,
                url = snapshot.url,
                summary = snapshot.summary,
                createdAt = snapshot.createdAt,
            ).apply {
                replaceTags(tags)
            },
        ).also { savedLink ->
            savedLink.createdAt = snapshot.createdAt
        }
    }

    private fun refreshExistingLink(
        existingLink: Link,
        snapshot: LinkSnapshot,
    ) {
        existingLink.title = snapshot.title
        if (snapshot.summary.isNotBlank()) {
            existingLink.summary = snapshot.summary
        }
        existingLink.createdAt = snapshot.createdAt
        linkRepository.save(existingLink)
    }

    private fun connectUserToLink(
        user: User,
        link: Link,
    ): Boolean {
        val userId = user.id ?: throw ApiException(DefaultStatus.SERVER_ERROR, "회사 사용자 ID가 없습니다")
        val linkId = link.id ?: throw ApiException(DefaultStatus.SERVER_ERROR, "링크 ID가 없습니다")

        if (userLinkRepository.findByUserIdAndLinkId(userId, linkId) == null) {
            userLinkRepository.save(UserLink(user = user, link = link))
            return true
        }

        return false
    }

    private fun resolveLinkTagNames(rawTagNames: String?): List<String> =
        rawTagNames.toLineList()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

    private fun String?.toLineList(): List<String> {
        return this?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toList()
            ?: emptyList()
    }
}

internal enum class LinkCrawlLinkCollectResult {
    CREATED_NEW_LINK,
    CONNECTED_EXISTING_LINK,
    UPDATED_EXISTING_LINK,
}

internal class LinkTagResolver(
    private val tagNames: List<String>,
    private val resolveTags: (Collection<String>) -> Set<Tag>,
) {
    private var resolvedTags: Set<Tag>? = null

    fun resolve(): Set<Tag> {
        if (tagNames.isEmpty()) {
            return emptySet()
        }
        resolvedTags?.let { return it }
        return resolveTags(tagNames).also { resolvedTags = it }
    }
}
