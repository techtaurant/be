package com.techtaurant.mainserver.link.entity

import com.techtaurant.mainserver.common.base.EntityBase
import com.techtaurant.mainserver.user.entity.User

class LinkViewLog(
    var link: Link,
    var user: User? = null,
    var ipAddress: String? = null,
    var userAgent: String? = null,
) : EntityBase()
