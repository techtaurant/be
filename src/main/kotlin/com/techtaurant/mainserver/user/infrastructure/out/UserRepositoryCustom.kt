package com.techtaurant.mainserver.user.infrastructure.out

import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import java.util.Optional
import java.util.UUID

interface UserRepositoryCustom {
    fun findById(id: UUID): Optional<User>

    fun findAllById(ids: Iterable<UUID>): List<User>

    fun findByIdentifierAndProvider(
        identifier: String,
        provider: OAuthProvider,
    ): User?

    fun findByNameContainingIgnoreCaseOrderByNameAsc(name: String): List<User>

    fun findAllByRoleOrderByNameAsc(role: UserRole): List<User>
}
