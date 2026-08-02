package com.techtaurant.mainserver.common.base

import java.time.Instant
import java.util.UUID

open class EntityBase(
    var id: UUID? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
