package com.techtaurant.mainserver.security.interlight

import com.techtaurant.mainserver.common.status.StatusIfs

enum class InterlightJwtStatus : StatusIfs {
    KEYS_UNAVAILABLE,
    ;

    override fun getHttpStatusCode(): Int = 503

    override fun getCustomStatusCode(): Int = 3201

    override fun getDescription(): String = "Interlight token signing is unavailable"
}
