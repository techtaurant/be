package com.techtaurant.mainserver.user.infrastructure.out

import com.github.f4b6a3.uuid.UuidCreator
import com.techtaurant.mainserver.jooq.tables.UserBans.Companion.USER_BANS
import com.techtaurant.mainserver.jooq.tables.Users.Companion.USERS
import com.techtaurant.mainserver.jooq.tables.records.UsersRecord
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.entity.UserBan
import com.techtaurant.mainserver.user.enums.UserRole
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Repository
class UserBanRepositoryCustomImpl(
    private val dsl: DSLContext,
) : UserBanRepository {
    private val bannedUser = USERS.`as`("banned_user")

    override fun save(userBan: UserBan): UserBan {
        val now = Instant.now()
        val id = userBan.id ?: UuidCreator.getTimeOrderedEpoch().also { userBan.id = it }
        dsl.insertInto(USER_BANS)
            .set(USER_BANS.ID, id)
            .set(USER_BANS.USER_ID, requireNotNull(userBan.user.id))
            .set(USER_BANS.BANNED_USER_ID, requireNotNull(userBan.bannedUser.id))
            .set(USER_BANS.CREATED_AT_UTC, now.atOffset(ZoneOffset.UTC))
            .set(USER_BANS.UPDATED_AT_UTC, now.atOffset(ZoneOffset.UTC))
            .execute()
        userBan.createdAt = now
        userBan.updatedAt = now
        return userBan
    }

    override fun delete(userBan: UserBan) {
        userBan.id?.let { dsl.deleteFrom(USER_BANS).where(USER_BANS.ID.eq(it)).execute() }
    }

    override fun deleteAllInBatch() {
        dsl.deleteFrom(USER_BANS).execute()
    }

    override fun findByUserIdAndBannedUserId(
        userId: UUID,
        bannedUserId: UUID,
    ): UserBan? = fetchUserBans(USER_BANS.USER_ID.eq(userId).and(USER_BANS.BANNED_USER_ID.eq(bannedUserId))).firstOrNull()

    override fun findAllByUserIdOrderByCreatedAtDesc(userId: UUID): List<UserBan> =
        fetchUserBans(USER_BANS.USER_ID.eq(userId), USER_BANS.CREATED_AT_UTC.desc())

    override fun findBannedUserIdsByUserId(userId: UUID): List<UUID> =
        dsl.select(
            USER_BANS.BANNED_USER_ID,
        ).from(USER_BANS).where(USER_BANS.USER_ID.eq(userId)).fetch(USER_BANS.BANNED_USER_ID).filterNotNull()

    private fun fetchUserBans(
        condition: Condition,
        vararg orderBy: org.jooq.SortField<*>,
    ): List<UserBan> =
        dsl.select(USER_BANS.asterisk(), bannedUser.asterisk())
            .from(USER_BANS)
            .join(bannedUser)
            .on(USER_BANS.BANNED_USER_ID.eq(bannedUser.ID))
            .where(condition)
            .orderBy(orderBy.asList())
            .fetch()
            .map(::toUserBan)

    private fun toUserBan(record: Record): UserBan {
        val userBanRecord = record.into(USER_BANS)
        val banned = record.into(bannedUser).toUser()
        val owner = userReference(requireNotNull(userBanRecord.userId))

        return UserBan(owner, banned).apply {
            id = requireNotNull(userBanRecord.id)
            createdAt = requireNotNull(userBanRecord.createdAtUtc).toInstant()
            updatedAt = requireNotNull(userBanRecord.updatedAtUtc).toInstant()
        }
    }

    private fun userReference(userId: UUID): User = User("", "", OAuthProvider.GOOGLE, "", UserRole.USER, "").apply { id = userId }

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
