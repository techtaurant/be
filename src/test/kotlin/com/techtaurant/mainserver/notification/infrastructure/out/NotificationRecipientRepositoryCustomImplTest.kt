package com.techtaurant.mainserver.notification.infrastructure.out

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.notification.entity.Notification
import com.techtaurant.mainserver.notification.entity.NotificationRecipient
import com.techtaurant.mainserver.notification.enums.NotificationType
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

@DisplayName("알림 수신자 저장 통합 테스트")
class NotificationRecipientRepositoryCustomImplTest : IntegrationTest() {
    @Autowired
    private lateinit var notificationRepository: NotificationRepository

    @Autowired
    private lateinit var notificationRecipientRepository: NotificationRecipientRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Test
    @DisplayName("읽음 상태 저장 후 반환 엔티티와 DB의 updatedAt이 일치한다")
    fun saveSynchronizesUpdatedAtWithDatabase() {
        // Given
        val user =
            userRepository.save(
                User(
                    name = "알림 사용자",
                    email = "notification-${UUID.randomUUID()}@example.com",
                    provider = OAuthProvider.SYSTEM,
                    identifier = "notification-${UUID.randomUUID()}",
                    role = UserRole.USER,
                    profileImageUrl = "",
                ),
            )
        val recipient = NotificationRecipient(Notification(NotificationType.POST_COMMENT), user)
        notificationRepository.save(recipient.notification.apply { addRecipient(recipient) })
        val readAt = Instant.parse("2026-08-01T00:00:00Z")
        recipient.readAt = readAt
        recipient.updatedAt = Instant.EPOCH

        // When
        val savedRecipient = notificationRecipientRepository.save(recipient)

        // Then
        val reloadedRecipient = notificationRecipientRepository.findById(savedRecipient.id!!).orElseThrow()
        assertThat(reloadedRecipient.readAt).isEqualTo(readAt)
        assertThat(savedRecipient.updatedAt).isEqualTo(reloadedRecipient.updatedAt)
    }
}
