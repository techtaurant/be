package com.techtaurant.mainserver.link.entity

import com.techtaurant.mainserver.common.base.EntityBase
import com.techtaurant.mainserver.user.entity.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(
    name = "link_crawl_batches",
    indexes = [
        Index(name = "idx_link_crawl_batches_company_user_id", columnList = "company_user_id"),
        Index(name = "idx_link_crawl_batches_active", columnList = "active"),
    ],
)
class LinkCrawlBatch(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_user_id", nullable = false)
    var companyUser: User,
    @Column(nullable = false, length = 100)
    var name: String,
    @Column(name = "base_url", nullable = false, length = 255)
    var baseUrl: String,
    @Column(name = "page_uri_template", nullable = false, length = 500)
    var pageUriTemplate: String,
    @Column(name = "item_selector", nullable = false, length = 500)
    var itemSelector: String,
    @Column(name = "article_link_selector", nullable = false, length = 500)
    var articleLinkSelector: String,
    @Column(name = "title_selector", nullable = false, length = 500)
    var titleSelector: String,
    @Column(name = "summary_selector", length = 500)
    var summarySelector: String? = null,
    @Column(name = "published_at_selectors", columnDefinition = "TEXT")
    var createdAtSelectors: String? = null,
    @Column(name = "tag_names", columnDefinition = "TEXT")
    var tagNames: String? = null,
    @Column(name = "cron_expression", nullable = false, length = 120)
    var cronExpression: String,
    @Column(name = "start_page", nullable = false)
    var startPage: Int = 1,
    @Column(name = "end_page", nullable = false)
    var endPage: Int = startPage,
    @Column(nullable = false)
    var active: Boolean = true,
    @Column(name = "last_triggered_at_utc")
    var lastTriggeredAt: Instant? = null,
) : EntityBase()
