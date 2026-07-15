package com.techtaurant.mainserver.user.entity

import com.techtaurant.mainserver.common.base.EntityBase

class UserFollow(
    val follower: User,
    val following: User,
) : EntityBase()
