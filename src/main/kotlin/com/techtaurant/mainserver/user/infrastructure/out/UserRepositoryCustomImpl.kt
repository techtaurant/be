package com.techtaurant.mainserver.user.infrastructure.out

import com.github.f4b6a3.uuid.UuidCreator
import com.techtaurant.mainserver.jooq.tables.Users.Companion.USERS
import com.techtaurant.mainserver.jooq.tables.records.UsersRecord
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

@Repository
class UserRepositoryCustomImpl(
    private val dsl: DSLContext,
) : UserRepository {
    override fun findById(id: UUID): Optional<User> = Optional.ofNullable(dsl.selectFrom(USERS).where(USERS.ID.eq(id)).fetchOne()?.toUser())

    override fun findAllById(ids: Iterable<UUID>): List<User> {
        val userIds = ids.toList()
        if (userIds.isEmpty()) {
            return emptyList()
        }

        return dsl.selectFrom(USERS).where(USERS.ID.`in`(userIds)).fetch().map { it.toUser() }
    }

    override fun findAll(): List<User> = dsl.selectFrom(USERS).fetch().map { it.toUser() }

    override fun existsById(id: UUID): Boolean = dsl.fetchExists(dsl.selectOne().from(USERS).where(USERS.ID.eq(id)))

    override fun count(): Long = dsl.fetchCount(USERS).toLong()

    override fun save(user: User): User {
        val userId = user.id ?: UuidCreator.getTimeOrderedEpoch().also { user.id = it }
        val now = Instant.now()

        if (existsById(userId)) {
            update(user, now)
        } else {
            user.createdAt = now
            user.updatedAt = now
            dsl.insertInto(USERS)
                .set(USERS.ID, userId)
                .set(USERS.NAME, user.name)
                .set(USERS.EMAIL, user.email)
                .set(USERS.PROVIDER, user.provider.name)
                .set(USERS.IDENTIFIER, user.identifier)
                .set(USERS.ROLE, user.role.name)
                .set(USERS.PROFILE_IMAGE_URL, user.profileImageUrl)
                .set(USERS.SERVICE_PROFILE_IMAGE_ATTACHMENT_ID, user.serviceProfileImageAttachmentId)
                .set(USERS.CREATED_AT_UTC, now.toUtcOffsetDateTime())
                .set(USERS.UPDATED_AT_UTC, now.toUtcOffsetDateTime())
                .execute()
        }
        return user
    }

    override fun saveAndFlush(user: User): User = save(user)

    override fun delete(user: User) {
        user.id?.let { dsl.deleteFrom(USERS).where(USERS.ID.eq(it)).execute() }
    }

    override fun deleteAllInBatch() {
        dsl.deleteFrom(USERS).execute()
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

    private fun update(
        user: User,
        now: Instant,
    ) {
        val userId = requireNotNull(user.id)
        dsl.update(USERS)
            .set(USERS.NAME, user.name)
            .set(USERS.EMAIL, user.email)
            .set(USERS.PROVIDER, user.provider.name)
            .set(USERS.IDENTIFIER, user.identifier)
            .set(USERS.ROLE, user.role.name)
            .set(USERS.PROFILE_IMAGE_URL, user.profileImageUrl)
            .set(USERS.SERVICE_PROFILE_IMAGE_ATTACHMENT_ID, user.serviceProfileImageAttachmentId)
            .set(USERS.UPDATED_AT_UTC, now.toUtcOffsetDateTime())
            .where(USERS.ID.eq(userId))
            .execute()
        user.updatedAt = now
    }

    private fun Instant.toUtcOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
