package com.techtaurant.mainserver.link.entity

import com.techtaurant.mainserver.common.base.EntityBase
import com.techtaurant.mainserver.user.entity.User

class LinkLikeLog(
    var link: Link,
    var user: User,
    var isLiked: Boolean = true,
) : EntityBase()
