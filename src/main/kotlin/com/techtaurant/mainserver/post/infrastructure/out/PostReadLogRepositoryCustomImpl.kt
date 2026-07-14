package com.techtaurant.mainserver.post.infrastructure.out

import com.techtaurant.mainserver.jooq.tables.PostReadLog.Companion.POST_READ_LOG
import com.techtaurant.mainserver.jooq.tables.records.PostReadLogRecord
import com.techtaurant.mainserver.post.entity.PostReadLog
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import jakarta.persistence.EntityManager
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class PostReadLogRepositoryCustomImpl(
    private val dsl: DSLContext,
    private val entityManager: EntityManager,
) : PostReadLogRepositoryCustom {
    override fun findByPostIdAndUserId(
        postId: UUID,
        userId: UUID,
    ): PostReadLog? =
        flushThen {
            dsl.selectFrom(
                POST_READ_LOG,
            ).where(POST_READ_LOG.POST_ID.eq(postId).and(POST_READ_LOG.USER_ID.eq(userId))).fetchOne()?.toPostReadLog()
        }

    override fun existsByPostIdAndUserId(
        postId: UUID,
        userId: UUID,
    ): Boolean = flushThen { dsl.fetchExists(POST_READ_LOG, POST_READ_LOG.POST_ID.eq(postId).and(POST_READ_LOG.USER_ID.eq(userId))) }

    override fun findByUserIdAndPostIdIn(
        userId: UUID,
        postIds: List<UUID>,
    ): List<PostReadLog> =
        if (postIds.isEmpty()) {
            emptyList()
        } else {
            flushThen {
                dsl.selectFrom(POST_READ_LOG).where(POST_READ_LOG.USER_ID.eq(userId).and(POST_READ_LOG.POST_ID.`in`(postIds))).fetch().map {
                    it.toPostReadLog()
                }
            }
        }

    private fun <T> flushThen(query: () -> T): T {
        if (entityManager.isJoinedToTransaction) {
            entityManager.flush()
        }
        return query()
    }

    private fun PostReadLogRecord.toPostReadLog(): PostReadLog =
        PostReadLog(requireNotNull(postId), userReference(requireNotNull(userId))).apply {
            id = requireNotNull(this@toPostReadLog.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }

    private fun userReference(userId: UUID): User = User("", "", OAuthProvider.GOOGLE, "", UserRole.USER, "").apply { id = userId }
}
