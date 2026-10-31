package com.techtaurant.mainserver.post.dto

import java.time.Instant
import java.util.UUID

data class PostForSitemap(val id: UUID, val updatedAt: Instant)
