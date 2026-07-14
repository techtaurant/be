package com.techtaurant.mainserver.user.infrastructure.out

import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface UserRepository : JpaRepository<User, UUID>, UserRepositoryCustom {
    override fun findById(id: UUID): Optional<User>

    override fun findAllById(ids: Iterable<UUID>): List<User>

    override fun findByIdentifierAndProvider(
        identifier: String,
        provider: OAuthProvider,
    ): User?

    override fun findByNameContainingIgnoreCaseOrderByNameAsc(name: String): List<User>

    override fun findAllByRoleOrderByNameAsc(role: UserRole): List<User>
}
