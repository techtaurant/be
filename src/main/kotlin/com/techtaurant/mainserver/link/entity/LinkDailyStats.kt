package com.techtaurant.mainserver.link.entity

import com.techtaurant.mainserver.common.base.EntityBase
import java.time.LocalDate

class LinkDailyStats(
    var link: Link,
    var statDate: LocalDate,
    var viewCount: Long = 0,
    var likeCount: Long = 0,
    var saveCount: Long = 0,
) : EntityBase()
