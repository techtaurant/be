package com.techtaurant.mainserver.common.persistence

import com.techtaurant.mainserver.attachment.entity.Attachment
import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.attachment.enums.AttachmentStatus
import com.techtaurant.mainserver.attachment.infrastructure.out.AttachmentRepository
import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.CannotAcquireLockException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DisplayName("영속성 행 잠금 통합 테스트")
class PersistenceRowLockIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var postRepository: PostRepository

    @Autowired
    private lateinit var attachmentRepository: AttachmentRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    @DisplayName("게시물 잠금 조회는 다른 트랜잭션의 같은 게시물 잠금 조회를 대기시킨다")
    fun findPostByIdWithAuthorForUpdate_samePost_waitsForExistingLock() {
        // given
        val author = createUser()
        val postId =
            postRepository.save(
                Post(
                    title = "잠금 대상 게시물",
                    content = "본문",
                    author = author,
                    status = PostStatusEnum.PUBLISHED,
                ),
            ).id!!

        // when & then
        assertSecondTransactionTimesOutWhileLockIsHeld {
            postRepository.findPostByIdWithAuthorForUpdate(postId)
        }
    }

    @Test
    @DisplayName("첨부 잠금 조회는 다른 트랜잭션의 같은 첨부 잠금 조회를 대기시킨다")
    fun findAllByIdForUpdate_sameAttachment_waitsForExistingLock() {
        // given
        val attachmentId =
            attachmentRepository.save(
                Attachment(
                    referenceId = null,
                    referenceType = AttachmentReferenceType.POST,
                    objectKey = "tmp/${UUID.randomUUID()}/image.png",
                    status = AttachmentStatus.TMP,
                    originalFileName = "image.png",
                    contentType = "image/png",
                    fileSize = 1024L,
                ),
            ).id!!

        // when & then
        assertSecondTransactionTimesOutWhileLockIsHeld {
            attachmentRepository.findAllByIdForUpdate(listOf(attachmentId))
        }
    }

    private fun assertSecondTransactionTimesOutWhileLockIsHeld(lockAction: () -> Unit) {
        val lockAcquired = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val lockHolder =
            executor.submit {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    lockAction()
                    lockAcquired.countDown()
                    check(releaseLock.await(5, TimeUnit.SECONDS)) { "행 잠금 해제 신호를 기다리지 못했습니다" }
                }
            }

        try {
            assertThat(lockAcquired.await(5, TimeUnit.SECONDS)).isTrue()

            assertThatThrownBy {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    jdbcTemplate.execute("SET LOCAL lock_timeout = '250ms'")
                    lockAction()
                }
            }.isInstanceOf(CannotAcquireLockException::class.java)
                .hasMessageContaining("canceling statement due to lock timeout")
        } finally {
            releaseLock.countDown()
            lockHolder.get(5, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }

    private fun createUser(): User =
        userRepository.save(
            User(
                name = "잠금 테스트 사용자",
                email = "lock-${UUID.randomUUID()}@example.com",
                provider = OAuthProvider.GOOGLE,
                identifier = "lock-${UUID.randomUUID()}",
                role = UserRole.USER,
                profileImageUrl = "https://example.com/profile.png",
            ),
        )
}
