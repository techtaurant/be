package com.techtaurant.mainserver.notification.infrastructure.`in`

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.notification.entity.Notification
import com.techtaurant.mainserver.notification.entity.NotificationArgument
import com.techtaurant.mainserver.notification.entity.NotificationRecipient
import com.techtaurant.mainserver.notification.enums.NotificationTargetType
import com.techtaurant.mainserver.notification.enums.NotificationType
import com.techtaurant.mainserver.notification.infrastructure.out.NotificationRecipientRepository
import com.techtaurant.mainserver.notification.infrastructure.out.NotificationRepository
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.restassured.RestAssured
import io.restassured.http.ContentType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("알림 API")
class NotificationControllerIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var notificationRepository: NotificationRepository

    @Autowired
    private lateinit var notificationRecipientRepository: NotificationRecipientRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var postRepository: PostRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    private lateinit var currentUser: User
    private lateinit var actorUser: User
    private lateinit var otherUser: User
    private lateinit var accessToken: String

    @BeforeEach
    fun setup() {
        currentUser = createUser("current-user")
        actorUser = createUser("actor-user")
        otherUser = createUser("other-user")
        accessToken = jwtTokenProvider.createAccessToken(currentUser.id!!, currentUser.role)
    }

    @Test
    @DisplayName("내 알림 목록 조회 성공 - 최신순으로 정렬되고 알림 썸네일은 payloadHtml과 별도 필드로 반환된다")
    fun getMyNotifications_returnsLatestFirstWithReadStateAndArguments() {
        val publishedPost = createPost(actorUser, "동적 payload 게시물")
        val olderNotification =
            createNotification(
                recipient = currentUser,
                actor = actorUser,
                type = NotificationType.FOLLOWER_POST,
                createdAt = Instant.parse("2026-04-24T01:00:00Z"),
                readAt = Instant.parse("2026-04-24T01:10:00Z"),
                targetType = NotificationTargetType.POST,
                targetId = publishedPost.id!!,
            )
        val newerNotification =
            createNotification(
                recipient = currentUser,
                actor = actorUser,
                type = NotificationType.FOLLOW,
                createdAt = Instant.parse("2026-04-24T02:00:00Z"),
            )
        createNotification(
            recipient = otherUser,
            actor = actorUser,
            type = NotificationType.FOLLOWER_POST,
            createdAt = Instant.parse("2026-04-24T03:00:00Z"),
        )

        val response =
            RestAssured
                .given()
                .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
                .queryParam("size", 10)
                .`when`()
                .get("/api/notifications")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()

        assertEquals(200, response.getInt("status"))
        assertEquals(2, response.getInt("data.size"))
        assertEquals(2, response.getList<Any>("data.content").size)
        assertEquals(newerNotification.notificationId.toString(), response.getString("data.content[0].id"))
        assertEquals("FOLLOW", response.getString("data.content[0].type"))
        assertEquals(false, response.getBoolean("data.content[0].isRead"))
        assertNull(response.getString("data.content[0].readAt"))
        assertFalse(response.getString("data.content[0].payloadHtml").contains("<img"))
        assertEquals("https://example.com/actor-user.png", response.getString("data.content[0].thumbnailUrl"))
        assertEquals(1, response.getList<Any>("data.content[0].arguments").size)
        assertEquals("USER", response.getString("data.content[0].arguments[0].targetType"))
        assertEquals(actorUser.id.toString(), response.getString("data.content[0].arguments[0].targetId"))
        assertEquals(olderNotification.notificationId.toString(), response.getString("data.content[1].id"))
        assertEquals(true, response.getBoolean("data.content[1].isRead"))
        assertNotNull(response.getString("data.content[1].readAt"))
        assertFalse(response.getString("data.content[1].payloadHtml").contains("<img"))
        assertTrue(response.getString("data.content[1].thumbnailUrl").contains("/static/images/post-thumbnail.png"))
        assertTrue(response.getString("data.content[1].payloadHtml").contains("동적 payload 게시물"))
    }

    @Test
    @DisplayName("내 알림 목록 조회 성공 - nextCursor로 다음 페이지를 이어서 조회할 수 있다")
    fun getMyNotifications_withCursor_returnsNextPage() {
        val oldestNotification =
            createNotification(
                recipient = currentUser,
                actor = actorUser,
                type = NotificationType.FOLLOW,
                createdAt = Instant.parse("2026-04-24T01:00:00Z"),
            )
        createNotification(
            recipient = currentUser,
            actor = actorUser,
            type = NotificationType.POST_COMMENT,
            createdAt = Instant.parse("2026-04-24T02:00:00Z"),
        )
        createNotification(
            recipient = currentUser,
            actor = actorUser,
            type = NotificationType.FOLLOWER_POST,
            createdAt = Instant.parse("2026-04-24T03:00:00Z"),
        )

        val firstPage =
            RestAssured
                .given()
                .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
                .queryParam("size", 2)
                .`when`()
                .get("/api/notifications")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()

        val nextCursor = firstPage.getString("data.nextCursor")
        assertNotNull(nextCursor)
        assertEquals(true, firstPage.getBoolean("data.hasNext"))
        assertEquals(2, firstPage.getInt("data.size"))

        val secondPage =
            RestAssured
                .given()
                .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
                .queryParam("size", 2)
                .queryParam("cursor", nextCursor)
                .`when`()
                .get("/api/notifications")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()

        assertEquals(1, secondPage.getInt("data.size"))
        assertEquals(false, secondPage.getBoolean("data.hasNext"))
        assertEquals(oldestNotification.notificationId.toString(), secondPage.getString("data.content[0].id"))
    }

    @Test
    @DisplayName("내 안읽은 알림 수 조회 성공 - 내 미읽음 알림만 카운트한다")
    fun getMyUnreadNotificationCount_returnsOnlyMyUnreadCount() {
        createNotification(
            recipient = currentUser,
            actor = actorUser,
            type = NotificationType.FOLLOW,
            createdAt = Instant.parse("2026-04-24T01:00:00Z"),
        )
        createNotification(
            recipient = currentUser,
            actor = actorUser,
            type = NotificationType.POST_COMMENT,
            createdAt = Instant.parse("2026-04-24T02:00:00Z"),
        )
        createNotification(
            recipient = currentUser,
            actor = actorUser,
            type = NotificationType.FOLLOWER_POST,
            createdAt = Instant.parse("2026-04-24T03:00:00Z"),
            readAt = Instant.parse("2026-04-24T03:10:00Z"),
        )
        createNotification(
            recipient = otherUser,
            actor = actorUser,
            type = NotificationType.FOLLOW,
            createdAt = Instant.parse("2026-04-24T04:00:00Z"),
        )

        val response =
            RestAssured
                .given()
                .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
                .`when`()
                .get("/api/notifications/unread-count")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()

        assertEquals(200, response.getInt("status"))
        assertEquals(2, response.getInt("data.unreadCount"))
    }

    @Test
    @DisplayName("알림 다건 읽음 처리 성공 - 내 미읽음 알림만 읽음 처리되고 타인 알림 ID는 무시된다")
    fun markNotificationsRead_marksOnlyOwnedUnreadNotifications() {
        val unreadNotification =
            createNotification(
                recipient = currentUser,
                actor = actorUser,
                type = NotificationType.FOLLOW,
                createdAt = Instant.parse("2026-04-24T01:00:00Z"),
            )
        val alreadyReadNotification =
            createNotification(
                recipient = currentUser,
                actor = actorUser,
                type = NotificationType.POST_COMMENT,
                createdAt = Instant.parse("2026-04-24T02:00:00Z"),
                readAt = Instant.parse("2026-04-24T02:10:00Z"),
            )
        val otherUsersNotification =
            createNotification(
                recipient = otherUser,
                actor = actorUser,
                type = NotificationType.FOLLOWER_POST,
                createdAt = Instant.parse("2026-04-24T03:00:00Z"),
            )

        val response =
            RestAssured
                .given()
                .contentType(ContentType.JSON)
                .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
                .body(
                    mapOf(
                        "notificationIds" to
                            listOf(
                                unreadNotification.notificationId,
                                alreadyReadNotification.notificationId,
                                otherUsersNotification.notificationId,
                            ),
                    ),
                )
                .`when`()
                .patch("/api/notifications/read")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()

        val updatedUnreadRecipient = notificationRecipientRepository.findById(unreadNotification.recipientId).orElseThrow()
        val unchangedReadRecipient = notificationRecipientRepository.findById(alreadyReadNotification.recipientId).orElseThrow()
        val untouchedOtherRecipient = notificationRecipientRepository.findById(otherUsersNotification.recipientId).orElseThrow()

        assertEquals(200, response.getInt("status"))
        assertEquals(1, response.getList<Any>("data.notifications").size)
        assertEquals(unreadNotification.notificationId.toString(), response.getString("data.notifications[0].id"))
        assertEquals(true, response.getBoolean("data.notifications[0].isRead"))
        assertNotNull(response.getString("data.notifications[0].readAt"))

        assertNotNull(updatedUnreadRecipient.readAt)
        assertNotNull(unchangedReadRecipient.readAt)
        assertNull(untouchedOtherRecipient.readAt)
    }

    @Test
    @DisplayName("내 알림 목록 조회 실패 - 인증 없이 요청하면 401을 반환한다")
    fun getMyNotifications_withoutAuthentication_returns401() {
        RestAssured
            .given()
            .queryParam("size", 10)
            .`when`()
            .get("/api/notifications")
            .then()
            .statusCode(401)
    }

    @Test
    @DisplayName("내 안읽은 알림 수 조회 실패 - 인증 없이 요청하면 401을 반환한다")
    fun getMyUnreadNotificationCount_withoutAuthentication_returns401() {
        RestAssured
            .given()
            .`when`()
            .get("/api/notifications/unread-count")
            .then()
            .statusCode(401)
    }

    @Test
    @DisplayName("알림 다건 읽음 처리 실패 - 인증 없이 요청하면 401을 반환한다")
    fun markNotificationsRead_withoutAuthentication_returns401() {
        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(mapOf("notificationIds" to listOf(UUID.randomUUID())))
            .`when`()
            .patch("/api/notifications/read")
            .then()
            .statusCode(401)
    }

    private fun createUser(namePrefix: String): User =
        userRepository.save(
            User(
                name = "$namePrefix-${UUID.randomUUID()}",
                email = "$namePrefix-${UUID.randomUUID()}@example.com",
                provider = OAuthProvider.GOOGLE,
                identifier = "$namePrefix-identifier-${UUID.randomUUID()}",
                role = UserRole.USER,
                profileImageUrl = "https://example.com/$namePrefix.png",
            ),
        )

    private fun createPost(
        author: User,
        title: String,
    ): Post =
        postRepository.save(
            Post(
                title = title,
                content = "<p>본문</p>",
                author = author,
            ),
        )

    private fun createNotification(
        recipient: User,
        actor: User,
        type: NotificationType,
        createdAt: Instant,
        readAt: Instant? = null,
        targetType: NotificationTargetType? = null,
        targetId: UUID? = null,
    ): CreatedNotification {
        val notification =
            Notification(
                type = type,
            )

        notification.addArgument(
            NotificationArgument(
                notification = notification,
                targetType = NotificationTargetType.USER,
                targetId = actor.id!!,
            ),
        )
        if (targetType != null && targetId != null) {
            notification.addArgument(
                NotificationArgument(
                    notification = notification,
                    targetType = targetType,
                    targetId = targetId,
                ),
            )
        }
        notification.addRecipient(
            NotificationRecipient(
                notification = notification,
                recipientUser = recipient,
                readAt = readAt,
            ),
        )

        val savedNotification = notificationRepository.save(notification)
        val savedRecipient = savedNotification.recipients.single()
        val createdAtDate = createdAt

        savedNotification.createdAt = createdAtDate
        savedNotification.updatedAt = createdAtDate
        savedRecipient.createdAt = createdAtDate
        savedRecipient.updatedAt = createdAtDate
        savedRecipient.readAt = readAt

        notificationRepository.save(savedNotification)
        notificationRecipientRepository.save(savedRecipient)

        return CreatedNotification(
            notificationId = savedNotification.id!!,
            recipientId = savedRecipient.id!!,
        )
    }

    private data class CreatedNotification(
        val notificationId: UUID,
        val recipientId: UUID,
    )
}
