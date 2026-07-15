package com.techtaurant.mainserver.link.entity

import com.techtaurant.mainserver.common.base.EntityBase
import com.techtaurant.mainserver.user.entity.User
import java.time.Instant

class LinkCrawlBatch(
    var companyUser: User,
    var name: String,
    var baseUrl: String,
    var pageUriTemplate: String,
    var itemSelector: String,
    var articleLinkSelector: String,
    var titleSelector: String,
    var summarySelector: String? = null,
    var createdAtSelectors: String? = null,
    var tagNames: String? = null,
    var cronExpression: String,
    var startPage: Int = 1,
    var endPage: Int = startPage,
    var active: Boolean = true,
    var lastTriggeredAt: Instant? = null,
) : EntityBase()
