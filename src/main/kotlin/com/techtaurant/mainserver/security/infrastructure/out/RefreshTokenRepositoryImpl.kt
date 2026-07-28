package com.techtaurant.mainserver.security.infrastructure.out

import com.github.f4b6a3.uuid.UuidCreator
import com.techtaurant.mainserver.jooq.tables.RefreshTokens.Companion.REFRESH_TOKENS
import com.techtaurant.mainserver.jooq.tables.Users.Companion.USERS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Repository
class RefreshTokenRepositoryImpl(
    private val dsl: DSLContext,
) : RefreshTokenRepository {
    override fun lockUser(userId: UUID): Boolean =
        dsl.select(USERS.ID)
            .from(USERS)
            .where(USERS.ID.eq(userId))
            .forUpdate()
            .fetchOne() != null

    override fun countByUserId(userId: UUID): Int =
        dsl.fetchCount(
            REFRESH_TOKENS,
            REFRESH_TOKENS.USER_ID.eq(userId),
        )

    override fun deleteOldestByUserId(
        userId: UUID,
        limit: Int,
    ): Int {
        if (limit <= 0) {
            return 0
        }

        val tokenIds =
            dsl.select(REFRESH_TOKENS.ID)
                .from(REFRESH_TOKENS)
                .where(REFRESH_TOKENS.USER_ID.eq(userId))
                .orderBy(REFRESH_TOKENS.CREATED_AT_UTC.asc(), REFRESH_TOKENS.ID.asc())
                .limit(limit)

        return dsl.deleteFrom(REFRESH_TOKENS)
            .where(REFRESH_TOKENS.ID.`in`(tokenIds))
            .execute()
    }

    override fun insert(
        userId: UUID,
        tokenHash: String,
    ) {
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        dsl.insertInto(REFRESH_TOKENS)
            .set(REFRESH_TOKENS.ID, UuidCreator.getTimeOrderedEpoch())
            .set(REFRESH_TOKENS.USER_ID, userId)
            .set(REFRESH_TOKENS.TOKEN_HASH, tokenHash)
            .set(REFRESH_TOKENS.CREATED_AT_UTC, now)
            .set(REFRESH_TOKENS.UPDATED_AT_UTC, now)
            .execute()
    }

    override fun rotate(
        userId: UUID,
        expectedHash: String,
        replacementHash: String,
    ): Boolean =
        dsl.update(REFRESH_TOKENS)
            .set(REFRESH_TOKENS.TOKEN_HASH, replacementHash)
            .set(REFRESH_TOKENS.UPDATED_AT_UTC, Instant.now().atOffset(ZoneOffset.UTC))
            .where(
                REFRESH_TOKENS.USER_ID.eq(userId)
                    .and(REFRESH_TOKENS.TOKEN_HASH.eq(expectedHash)),
            )
            .execute() == 1

    override fun deleteAllByUserId(userId: UUID): Int =
        dsl.deleteFrom(REFRESH_TOKENS)
            .where(REFRESH_TOKENS.USER_ID.eq(userId))
            .execute()
}
