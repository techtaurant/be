package com.techtaurant.mainserver.link.entity

import com.techtaurant.mainserver.common.base.EntityBase
import com.techtaurant.mainserver.post.entity.Tag
import com.techtaurant.mainserver.post.entity.TaggedContent
import java.time.Instant

class Link(
    title: String,
    url: String,
    summary: String,
    tags: MutableSet<Tag> = mutableSetOf(),
    viewCount: Long = 0,
    likeCount: Long = 0,
    createdAt: Instant = Instant.now(),
) : EntityBase(createdAt = createdAt), TaggedContent {
    var title: String = title
        set(value) {
            validateTitle(value)
            field = value
        }
    var url: String = url
        set(value) {
            validateUrl(value)
            field = value
        }
    var summary: String = summary
    override var tags: MutableSet<Tag> = tags
    var viewCount: Long = viewCount
    var likeCount: Long = likeCount

    init {
        validateTitle(title)
        validateUrl(url)
        validateTagCount()
    }

    companion object {
        const val TITLE_MAX_LENGTH = 200
        const val URL_MAX_LENGTH = 2048

        fun validateTitle(title: String) {
            if (title.length > TITLE_MAX_LENGTH) {
                throw IllegalArgumentException("링크 제목은 ${TITLE_MAX_LENGTH}자를 초과할 수 없습니다")
            }
        }

        fun validateUrl(url: String) {
            if (url.length > URL_MAX_LENGTH) {
                throw IllegalArgumentException("링크 URL은 ${URL_MAX_LENGTH}자를 초과할 수 없습니다")
            }
        }
    }
}
