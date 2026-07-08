package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.common.status.DefaultStatus
import com.techtaurant.mainserver.link.entity.Link
import com.techtaurant.mainserver.link.entity.LinkCrawlBatch
import com.techtaurant.mainserver.link.entity.UserLink
import com.techtaurant.mainserver.link.infrastructure.out.LinkRepository
import com.techtaurant.mainserver.link.infrastructure.out.UserLinkRepository
import com.techtaurant.mainserver.post.application.TagWriteService
import com.techtaurant.mainserver.post.entity.Tag
import com.techtaurant.mainserver.user.entity.User
import org.springframework.stereotype.Service

@Service
class LinkCrawlLinkCollector(
    private val linkRepository: LinkRepository,
    private val userLinkRepository: UserLinkRepository,
    private val tagWriteService: TagWriteService,
) {
    internal fun tagResolverFor(batch: LinkCrawlBatch): LinkTagResolver {
        return LinkTagResolver(resolveLinkTagNames(batch.tagNames), tagWriteService::resolveTags)
    }

    internal fun collect(
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
