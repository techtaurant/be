package com.techtaurant.mainserver.user.application

import com.techtaurant.mainserver.attachment.application.AttachmentService
import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.common.status.DefaultStatus
import com.techtaurant.mainserver.user.dto.UpdateUserRequest
import com.techtaurant.mainserver.user.dto.UpdateUserRoleResponse
import com.techtaurant.mainserver.user.dto.UserResponse
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.enums.UserStatus
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import com.techtaurant.mainserver.user.infrastructure.out.UserTokenRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class UserWriteService(
    private val userRepository: UserRepository,
    private val attachmentService: AttachmentService,
    private val userResponseAssembler: UserResponseAssembler,
    private val userTokenRepository: UserTokenRepository,
) {
    companion object {
        private const val USER_NAME_UNIQUE_CONSTRAINT = "uk_users_name"
    }

    @Transactional
    fun updateMe(
        userId: UUID,
        request: UpdateUserRequest,
    ): UserResponse {
        val user =
            userRepository.findById(userId).orElseThrow {
                ApiException(UserStatus.ID_NOT_FOUND)
            }

        request.name?.let { requestedName ->
            val normalizedName = requestedName.trim()
            if (normalizedName.isEmpty()) {
                throw ApiException(DefaultStatus.BAD_REQUEST, "이름은 공백일 수 없습니다")
            }
            user.name = normalizedName
        }

        if (request.hasServiceProfileImageAttachmentId()) {
            val attachmentId = request.parseServiceProfileImageAttachmentId()
            user.serviceProfileImageAttachmentId = attachmentId

            if (attachmentId != null) {
                attachmentService.confirmAttachmentsByIds(
                    referenceId = userId,
                    referenceType = AttachmentReferenceType.USER,
                    attachmentIds = listOf(attachmentId),
                )
            }

            attachmentService.deleteOrphanedAttachmentsByIds(
                referenceId = userId,
                referenceType = AttachmentReferenceType.USER,
                keepAttachmentIds = listOfNotNull(attachmentId),
            )
        }

        if (request.name != null || request.hasServiceProfileImageAttachmentId()) {
            try {
                userRepository.save(user)
            } catch (exception: DataIntegrityViolationException) {
                if (request.name != null && isUserNameUniqueConstraintViolation(exception)) {
                    throw ApiException(UserStatus.USER_NAME_ALREADY_EXISTS)
                }
                throw exception
            }
        }

        return userResponseAssembler.assemble(user)
    }

    @Transactional
    fun updateUserRole(
        targetUserId: UUID,
        role: UserRole,
    ): UpdateUserRoleResponse {
        val user =
            userRepository.findById(targetUserId).orElseThrow {
                ApiException(UserStatus.USER_NOT_FOUND)
            }

        revokePermanentTokensOnRoleChange(targetUserId, user.role, role)
        user.role = role
        userRepository.save(user)

        return UpdateUserRoleResponse.from(user)
    }

    private fun revokePermanentTokensOnRoleChange(
        targetUserId: UUID,
        currentRole: UserRole,
        requestedRole: UserRole,
    ) {
        if (currentRole != requestedRole) {
            userTokenRepository.deleteAllByUserId(targetUserId)
        }
    }

    private fun isUserNameUniqueConstraintViolation(exception: DataIntegrityViolationException): Boolean {
        return generateSequence<Throwable>(exception) { current -> current.cause }
            .mapNotNull { throwable -> throwable.message }
            .any { message -> message.contains(USER_NAME_UNIQUE_CONSTRAINT, ignoreCase = true) }
    }
}
