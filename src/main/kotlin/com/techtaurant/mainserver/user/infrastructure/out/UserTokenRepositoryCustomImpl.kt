package com.techtaurant.mainserver.user.infrastructure.out

import com.github.f4b6a3.uuid.UuidCreator
import com.techtaurant.mainserver.jooq.tables.UserTokens.Companion.USER_TOKENS
import com.techtaurant.mainserver.jooq.tables.Users.Companion.USERS
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.entity.UserToken
import com.techtaurant.mainserver.user.enums.UserRole
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Repository
class UserTokenRepositoryCustomImpl(
    private val dsl: DSLContext,
) : UserTokenRepositoryCustom {
    override fun saveAndFlush(userToken: UserToken): UserToken {
        val now = Instant.now()
        val id = userToken.id ?: UuidCreator.getTimeOrderedEpoch().also { userToken.id = it }
        dsl.insertInto(USER_TOKENS)
            .set(USER_TOKENS.ID, id)
            .set(USER_TOKENS.USER_ID, requireNotNull(userToken.user.id))
            .set(USER_TOKENS.NAME, userToken.name)
            .set(USER_TOKENS.TOKEN_HASH, userToken.tokenHash)
            .set(USER_TOKENS.CREATED_AT_UTC, now.atOffset(ZoneOffset.UTC))
            .set(USER_TOKENS.UPDATED_AT_UTC, now.atOffset(ZoneOffset.UTC))
            .execute()
        userToken.createdAt = now
        userToken.updatedAt = now
        return userToken
    }

    override fun deleteAllByUserId(userId: UUID): Long =
        dsl.deleteFrom(USER_TOKENS).where(USER_TOKENS.USER_ID.eq(userId)).execute().toLong()

    override fun deleteAllInBatch() {
        dsl.deleteFrom(USER_TOKENS).execute()
    }

    override fun findAll(): List<UserToken> =
        dsl.selectFrom(USER_TOKENS).fetch().map { record ->
            UserToken(
                user =
                    User("", "", com.techtaurant.mainserver.security.enums.OAuthProvider.GOOGLE, "", UserRole.USER, "").apply {
                        id = requireNotNull(record.userId)
                    },
                name = requireNotNull(record.name),
                tokenHash = requireNotNull(record.tokenHash),
            ).apply {
                id = requireNotNull(record.id)
                createdAt = requireNotNull(record.createdAtUtc).toInstant()
                updatedAt = requireNotNull(record.updatedAtUtc).toInstant()
            }
        }

    override fun count(): Long = dsl.fetchCount(USER_TOKENS).toLong()

    override fun flush() = Unit

    override fun existsByUserIdAndTokenHashAndUserRole(
        userId: UUID,
        tokenHash: String,
        role: UserRole,
    ): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(USER_TOKENS)
                .join(USERS)
                .on(USER_TOKENS.USER_ID.eq(USERS.ID))
                .where(USER_TOKENS.USER_ID.eq(userId).and(USER_TOKENS.TOKEN_HASH.eq(tokenHash)).and(USERS.ROLE.eq(role.name))),
        )
}
