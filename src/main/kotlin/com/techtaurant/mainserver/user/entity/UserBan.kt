package com.techtaurant.mainserver.user.entity

import com.techtaurant.mainserver.common.base.EntityBase

class UserBan(
    val user: User,
    val bannedUser: User,
) : EntityBase()
