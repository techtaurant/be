package com.techtaurant.mainserver.link.entity

import com.techtaurant.mainserver.common.base.EntityBase
import com.techtaurant.mainserver.post.entity.Tag
import com.techtaurant.mainserver.post.entity.TaggedContent
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "links",
    uniqueConstraints = [UniqueConstraint(name = "uk_links_url", columnNames = ["url"])],
)
class Link(
    title: String,
    url: String,
    summary: String,
    tags: MutableSet<Tag> = mutableSetOf(),
    viewCount: Long = 0,
    likeCount: Long = 0,
    createdAt: Instant = Instant.now(),
) : EntityBase(createdAt = createdAt), TaggedContent {
    @Column(nullable = false, length = TITLE_MAX_LENGTH)
    var title: String = title
        set(value) {
            validateTitle(value)
            field = value
        }

    @Column(nullable = false, length = URL_MAX_LENGTH)
    var url: String = url
        set(value) {
            validateUrl(value)
            field = value
        }

    @Column(nullable = false, columnDefinition = "TEXT")
    var summary: String = summary

    @ManyToMany(fetch = FetchType.LAZY, cascade = [CascadeType.PERSIST, CascadeType.MERGE])
    @JoinTable(
        name = "link_tags",
        joinColumns = [JoinColumn(name = "link_id")],
        inverseJoinColumns = [JoinColumn(name = "tag_id")],
    )
    override var tags: MutableSet<Tag> = tags

    @Column(name = "view_count", nullable = false)
    var viewCount: Long = viewCount

    @Column(name = "like_count", nullable = false)
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
