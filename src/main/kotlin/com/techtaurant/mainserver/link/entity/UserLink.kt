package com.techtaurant.mainserver.link.entity

import com.techtaurant.mainserver.common.base.EntityBase
import com.techtaurant.mainserver.user.entity.User

class UserLink(
    var user: User,
    var link: Link,
) : EntityBase()
