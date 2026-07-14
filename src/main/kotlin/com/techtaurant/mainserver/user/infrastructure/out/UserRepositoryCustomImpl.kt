package com.techtaurant.mainserver.user.infrastructure.out

import com.techtaurant.mainserver.jooq.tables.Users.Companion.USERS
import com.techtaurant.mainserver.jooq.tables.records.UsersRecord
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import jakarta.persistence.EntityManager
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.util.Optional
import java.util.UUID

@Repository
class UserRepositoryCustomImpl(
    private val dsl: DSLContext,
    private val entityManager: EntityManager,
) : UserRepositoryCustom {
    override fun findById(id: UUID): Optional<User> =
        flushThen { Optional.ofNullable(dsl.selectFrom(USERS).where(USERS.ID.eq(id)).fetchOne()?.toUser()) }

    override fun findAllById(ids: Iterable<UUID>): List<User> {
        val userIds = ids.toList()
        if (userIds.isEmpty()) {
            return emptyList()
        }

        return flushThen { dsl.selectFrom(USERS).where(USERS.ID.`in`(userIds)).fetch().map { it.toUser() } }
    }

    override fun findByIdentifierAndProvider(
        identifier: String,
        provider: OAuthProvider,
    ): User? =
        dsl.selectFrom(USERS)
            .where(USERS.IDENTIFIER.eq(identifier).and(USERS.PROVIDER.eq(provider.name)))
            .fetchOne()
            ?.toUser()

    override fun findByNameContainingIgnoreCaseOrderByNameAsc(name: String): List<User> {
        val normalizedName = name.trim()
        val similarity = DSL.function("similarity", Double::class.java, USERS.NAME, DSL.inline(normalizedName))

        return dsl.selectFrom(USERS)
            .where(USERS.NAME.likeIgnoreCase("%$normalizedName%"))
            .orderBy(similarity.desc(), USERS.NAME.asc())
            .fetch()
            .map { record -> record.toUser() }
    }

    override fun findAllByRoleOrderByNameAsc(role: UserRole): List<User> =
        dsl.selectFrom(USERS)
            .where(USERS.ROLE.eq(role.name))
            .orderBy(USERS.NAME.asc())
            .fetch()
            .map { record -> record.toUser() }

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

    private fun <T> flushThen(query: () -> T): T {
        if (entityManager.isJoinedToTransaction) {
            entityManager.flush()
        }
        return query()
    }
}
