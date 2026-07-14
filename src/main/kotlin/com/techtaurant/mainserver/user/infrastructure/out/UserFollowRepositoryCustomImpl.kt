package com.techtaurant.mainserver.user.infrastructure.out

import com.techtaurant.mainserver.jooq.tables.UserFollows.Companion.USER_FOLLOWS
import com.techtaurant.mainserver.jooq.tables.Users.Companion.USERS
import com.techtaurant.mainserver.jooq.tables.records.UsersRecord
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.entity.UserFollow
import com.techtaurant.mainserver.user.enums.UserRole
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class UserFollowRepositoryCustomImpl(
    private val dsl: DSLContext,
) : UserFollowRepositoryCustom {
    private val follower = USERS.`as`("follower")
    private val following = USERS.`as`("following")

    override fun findByFollowerIdAndFollowingId(
        followerId: UUID,
        followingId: UUID,
    ): UserFollow? = fetchUserFollows(USER_FOLLOWS.FOLLOWER_ID.eq(followerId).and(USER_FOLLOWS.FOLLOWING_ID.eq(followingId))).firstOrNull()

    override fun countByFollowerId(followerId: UUID): Long = dsl.fetchCount(USER_FOLLOWS, USER_FOLLOWS.FOLLOWER_ID.eq(followerId)).toLong()

    override fun countByFollowingId(followingId: UUID): Long =
        dsl.fetchCount(USER_FOLLOWS, USER_FOLLOWS.FOLLOWING_ID.eq(followingId)).toLong()

    override fun findAllByFollowerIdOrderByCreatedAtDesc(followerId: UUID): List<UserFollow> =
        fetchUserFollows(USER_FOLLOWS.FOLLOWER_ID.eq(followerId), USER_FOLLOWS.CREATED_AT_UTC.desc())

    override fun findAllByFollowingIdOrderByCreatedAtDesc(followingId: UUID): List<UserFollow> =
        fetchUserFollows(USER_FOLLOWS.FOLLOWING_ID.eq(followingId), USER_FOLLOWS.CREATED_AT_UTC.desc())

    override fun findFollowerIdsByFollowingId(followingId: UUID): List<UUID> =
        dsl.select(USER_FOLLOWS.FOLLOWER_ID)
            .from(USER_FOLLOWS)
            .where(USER_FOLLOWS.FOLLOWING_ID.eq(followingId))
            .orderBy(USER_FOLLOWS.CREATED_AT_UTC.desc())
            .fetch(USER_FOLLOWS.FOLLOWER_ID)
            .filterNotNull()

    private fun fetchUserFollows(
        condition: Condition,
        vararg orderBy: org.jooq.SortField<*>,
    ): List<UserFollow> =
        dsl.select(USER_FOLLOWS.asterisk(), follower.asterisk(), following.asterisk())
            .from(USER_FOLLOWS)
            .join(follower)
            .on(USER_FOLLOWS.FOLLOWER_ID.eq(follower.ID))
            .join(following)
            .on(USER_FOLLOWS.FOLLOWING_ID.eq(following.ID))
            .where(condition)
            .orderBy(orderBy.asList())
            .fetch()
            .map(::toUserFollow)

    private fun toUserFollow(record: Record): UserFollow {
        val followRecord = record.into(USER_FOLLOWS)
        return UserFollow(record.into(follower).toUser(), record.into(following).toUser()).apply {
            id = requireNotNull(followRecord.id)
            createdAt = requireNotNull(followRecord.createdAtUtc).toInstant()
            updatedAt = requireNotNull(followRecord.updatedAtUtc).toInstant()
        }
    }

    private fun UsersRecord.toUser(): User =
        User(
            name = requireNotNull(name),
            email = requireNotNull(email),
            provider = OAuthProvider.valueOf(requireNotNull(provider)),
            identifier = requireNotNull(identifier),
            role = UserRole.valueOf(requireNotNull(role)),
            profileImageUrl = profileImageUrl.orEmpty(),
            serviceProfileImageAttachmentId = serviceProfileImageAttachmentId,
        ).apply {
            id = requireNotNull(this@toUser.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }
}
