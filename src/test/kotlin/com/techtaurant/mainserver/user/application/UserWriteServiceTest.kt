package com.techtaurant.mainserver.user.application

import com.techtaurant.mainserver.attachment.application.AttachmentService
import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.dto.UpdateUserRequest
import com.techtaurant.mainserver.user.dto.UserResponse
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.enums.UserStatus
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import com.techtaurant.mainserver.user.infrastructure.out.UserTokenRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.util.Optional
import java.util.UUID
import kotlin.reflect.full.memberFunctions

class UserWriteServiceTest {
    private val userRepository: UserRepository = mockk()
    private val attachmentService: AttachmentService = mockk()
    private val userResponseAssembler: UserResponseAssembler = mockk()
    private val userTokenRepository: UserTokenRepository = mockk()

    private val userWriteService =
        UserWriteService(
            userRepository = userRepository,
            attachmentService = attachmentService,
            userResponseAssembler = userResponseAssembler,
            userTokenRepository = userTokenRepository,
        )

    private lateinit var user: User

    @BeforeEach
    fun setUp() {
        user =
            User(
                name = "기존이름",
                email = "user@example.com",
                provider = OAuthProvider.GOOGLE,
                identifier = "google-${UUID.randomUUID()}",
                role = UserRole.USER,
                profileImageUrl = "https://example.com/default-profile.jpg",
            ).apply { id = UUID.randomUUID() }

        every { userRepository.findById(user.id!!) } returns Optional.of(user)
        every {
            userResponseAssembler.assemble(any<User>())
        } answers {
            val targetUser = firstArg<User>()
            UserResponse.from(targetUser, targetUser.profileImageUrl)
        }
        every { userRepository.save(any()) } answers { firstArg() }
        every { userTokenRepository.deleteAllByUserId(any()) } returns 0
        every { attachmentService.confirmAttachmentsByIds(any(), any(), any()) } just runs
        every { attachmentService.deleteOrphanedAttachmentsByIds(any(), any(), any()) } just runs
    }

    @Test
    @DisplayName("이름만 수정하면 공백을 제거하고 attachment 작업은 수행하지 않는다")
    fun updateMe_nameOnly_updatesTrimmedName() {
        val response =
            userWriteService.updateMe(
                userId = user.id!!,
                request = UpdateUserRequest(name = "  새이름  "),
            )

        assertThat(user.name).isEqualTo("새이름")
        assertThat(response.name).isEqualTo("새이름")

        verify(exactly = 0) { attachmentService.confirmAttachmentsByIds(any(), any(), any()) }
        verify(exactly = 0) { attachmentService.deleteOrphanedAttachmentsByIds(any(), any(), any()) }
    }

    @Test
    @DisplayName("서비스 프로필 이미지를 설정하면 USER attachment를 확정하고 해당 ID만 유지한다")
    fun updateMe_serviceProfileImage_confirmsAndKeepsAttachment() {
        val attachmentId = UUID.randomUUID()

        userWriteService.updateMe(
            userId = user.id!!,
            request = UpdateUserRequest(serviceProfileImageAttachmentId = attachmentId.toString()),
        )

        assertThat(user.serviceProfileImageAttachmentId).isEqualTo(attachmentId)
        verify {
            attachmentService.confirmAttachmentsByIds(
                referenceId = user.id!!,
                referenceType = AttachmentReferenceType.USER,
                attachmentIds = listOf(attachmentId),
            )
        }
        verify {
            attachmentService.deleteOrphanedAttachmentsByIds(
                referenceId = user.id!!,
                referenceType = AttachmentReferenceType.USER,
                keepAttachmentIds = listOf(attachmentId),
            )
        }
    }

    @Test
    @DisplayName("서비스 프로필 이미지에 null을 보내면 기존 attachment 연결을 유지한다")
    fun updateMe_nullServiceProfileImage_keepsAttachment() {
        val existingAttachmentId = UUID.randomUUID()
        user.serviceProfileImageAttachmentId = existingAttachmentId

        // given

        // when
        userWriteService.updateMe(
            userId = user.id!!,
            request = UpdateUserRequest(serviceProfileImageAttachmentId = null),
        )

        // then
        assertThat(user.serviceProfileImageAttachmentId).isEqualTo(existingAttachmentId)
        verify(exactly = 0) { attachmentService.confirmAttachmentsByIds(any(), any(), any()) }
        verify(exactly = 0) { attachmentService.deleteOrphanedAttachmentsByIds(any(), any(), any()) }
    }

    @Test
    @DisplayName("이름이 공백만 있으면 BAD_REQUEST 예외를 던진다")
    fun updateMe_blankName_throwsBadRequest() {
        assertThatThrownBy {
            userWriteService.updateMe(
                userId = user.id!!,
                request = UpdateUserRequest(name = "   "),
            )
        }
            .isInstanceOf(ApiException::class.java)
            .hasMessage("이름은 공백일 수 없습니다")
    }

    @Test
    @DisplayName("중복 닉네임으로 수정하면 중복 닉네임 예외를 던진다")
    fun updateMe_duplicateName_throwsConflictApiException() {
        // given
        every {
            userRepository.save(any())
        } throws DataIntegrityViolationException("duplicate key value violates unique constraint \"uk_users_name\"")

        // when & then
        assertThatThrownBy {
            userWriteService.updateMe(
                userId = user.id!!,
                request = UpdateUserRequest(name = "중복닉네임"),
            )
        }
            .isInstanceOf(ApiException::class.java)
            .extracting { (it as ApiException).status }
            .isEqualTo(UserStatus.USER_NAME_ALREADY_EXISTS)
    }

    @Test
    @DisplayName("역할 변경 메서드는 대상 사용자를 ADMIN으로 변경한다")
    fun updateUserRole_updatesRole() {
        val method =
            UserWriteService::class.memberFunctions.firstOrNull { function ->
                function.name == "updateUserRole"
            } ?: error("updateUserRole 메서드가 없습니다")

        method.call(userWriteService, user.id!!, UserRole.ADMIN)

        assertThat(user.role).isEqualTo(UserRole.ADMIN)
        verify { userTokenRepository.deleteAllByUserId(user.id!!) }
    }

    @Test
    @DisplayName("역할이 변경되지 않으면 영구 토큰을 삭제하지 않는다")
    fun updateUserRole_sameRole_keepsPermanentToken() {
        val method =
            UserWriteService::class.memberFunctions.firstOrNull { function ->
                function.name == "updateUserRole"
            } ?: error("updateUserRole 메서드가 없습니다")

        method.call(userWriteService, user.id!!, UserRole.USER)

        assertThat(user.role).isEqualTo(UserRole.USER)
        verify(exactly = 0) { userTokenRepository.deleteAllByUserId(any()) }
    }

    @Test
    @DisplayName("역할 변경 대상 사용자가 없으면 USER_NOT_FOUND 예외를 던진다")
    fun updateUserRole_missingUser_throwsUserNotFound() {
        val method =
            UserWriteService::class.memberFunctions.firstOrNull { function ->
                function.name == "updateUserRole"
            } ?: error("updateUserRole 메서드가 없습니다")
        val missingUserId = UUID.randomUUID()
        every { userRepository.findById(missingUserId) } returns Optional.empty()

        assertThatThrownBy {
            method.call(userWriteService, missingUserId, UserRole.ADMIN)
        }
            .hasCauseInstanceOf(ApiException::class.java)
            .extracting { (it.cause as ApiException).status }
            .isEqualTo(UserStatus.USER_NOT_FOUND)
    }
}
