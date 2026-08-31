package com.techtaurant.mainserver.security.infrastructure.out

import com.github.f4b6a3.uuid.UuidCreator
import com.techtaurant.mainserver.jooq.tables.UserRefreshTokens.Companion.USER_REFRESH_TOKENS
import com.techtaurant.mainserver.security.jwt.JwtProperties
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqRefreshTokenStore(
    private val dsl: DSLContext,
    private val jwtTokenProvider: JwtTokenProvider,
    private val jwtProperties: JwtProperties,
) : RefreshTokenStore {
    /**
     * 사용자당 행 수를 제한하지 않으므로, 새 세션을 추가할 때 같은 사용자의 만료된 세션을 함께 걷어낸다.
     */
    override fun save(
        userId: UUID,
        refreshToken: String,
    ) {
        val now = Instant.now().atOffset(ZoneOffset.UTC)

        deleteExpired(userId, now)

        dsl.insertInto(USER_REFRESH_TOKENS)
            .set(USER_REFRESH_TOKENS.ID, UuidCreator.getTimeOrderedEpoch())
            .set(USER_REFRESH_TOKENS.USER_ID, userId)
            .set(USER_REFRESH_TOKENS.TOKEN_HASH, jwtTokenProvider.hashToken(refreshToken))
            .set(USER_REFRESH_TOKENS.EXPIRES_AT, now.plusNanos(jwtProperties.refreshTokenExpireMs * NANOS_PER_MILLI))
            .set(USER_REFRESH_TOKENS.CREATED_AT_UTC, now)
            .set(USER_REFRESH_TOKENS.UPDATED_AT_UTC, now)
            .execute()
    }

    override fun exists(
        userId: UUID,
        refreshToken: String,
    ): Boolean {
        return dsl.fetchExists(
            dsl.selectOne()
                .from(USER_REFRESH_TOKENS)
                .where(
                    USER_REFRESH_TOKENS.USER_ID.eq(userId)
                        .and(USER_REFRESH_TOKENS.TOKEN_HASH.eq(jwtTokenProvider.hashToken(refreshToken)))
                        .and(USER_REFRESH_TOKENS.EXPIRES_AT.gt(Instant.now().atOffset(ZoneOffset.UTC))),
                ),
        )
    }

    override fun delete(
        userId: UUID,
        refreshToken: String,
    ) {
        dsl.deleteFrom(USER_REFRESH_TOKENS)
            .where(
                USER_REFRESH_TOKENS.USER_ID.eq(userId)
                    .and(USER_REFRESH_TOKENS.TOKEN_HASH.eq(jwtTokenProvider.hashToken(refreshToken))),
            )
            .execute()
    }

    private fun deleteExpired(
        userId: UUID,
        now: OffsetDateTime,
    ) {
        dsl.deleteFrom(USER_REFRESH_TOKENS)
            .where(USER_REFRESH_TOKENS.USER_ID.eq(userId).and(USER_REFRESH_TOKENS.EXPIRES_AT.le(now)))
            .execute()
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
