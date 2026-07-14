package com.techtaurant.mainserver.user.infrastructure.out

import com.techtaurant.mainserver.jooq.tables.UserTokens.Companion.USER_TOKENS
import com.techtaurant.mainserver.jooq.tables.Users.Companion.USERS
import com.techtaurant.mainserver.user.enums.UserRole
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class UserTokenRepositoryCustomImpl(
    private val dsl: DSLContext,
) : UserTokenRepositoryCustom {
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
