package com.techtaurant.mainserver.user.entity

import com.techtaurant.mainserver.common.base.EntityBase

class UserToken(
    val user: User,
    var name: String,
    var tokenHash: String,
) : EntityBase()
