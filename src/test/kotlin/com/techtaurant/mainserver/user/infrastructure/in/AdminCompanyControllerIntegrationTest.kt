package com.techtaurant.mainserver.user.infrastructure.`in`

import com.techtaurant.mainserver.attachment.application.S3StorageService
import com.techtaurant.mainserver.attachment.entity.Attachment
import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import com.techtaurant.mainserver.attachment.enums.AttachmentStatus
import com.techtaurant.mainserver.attachment.infrastructure.out.AttachmentRepository
import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.comment.entity.Comment
import com.techtaurant.mainserver.comment.infrastructure.out.CommentRepository
import com.techtaurant.mainserver.link.entity.Link
import com.techtaurant.mainserver.link.entity.UserLink
import com.techtaurant.mainserver.link.infrastructure.out.LinkRepository
import com.techtaurant.mainserver.link.infrastructure.out.UserLinkRepository
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import com.techtaurant.mainserver.user.infrastructure.out.UserTokenRepository
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("AdminCompanyController 통합 테스트")
class AdminCompanyControllerIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var userTokenRepository: UserTokenRepository

    @Autowired
    private lateinit var attachmentRepository: AttachmentRepository

    @Autowired
    private lateinit var postRepository: PostRepository

    @Autowired
    private lateinit var commentRepository: CommentRepository

    @Autowired
    private lateinit var linkRepository: LinkRepository

    @Autowired
    private lateinit var userLinkRepository: UserLinkRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @MockitoBean
    private lateinit var s3StorageService: S3StorageService

    private lateinit var adminUser: User
    private lateinit var normalUser: User
    private lateinit var adminAccessToken: String
    private lateinit var userAccessToken: String

    @BeforeEach
    fun setUpTestData() {
        userRepository.deleteAllInBatch()

        adminUser =
            userRepository.save(
                User(
                    name = "관리자",
                    email = "admin-${UUID.randomUUID()}@example.com",
                    provider = OAuthProvider.GOOGLE,
                    identifier = "admin-id-${UUID.randomUUID()}",
                    role = UserRole.ADMIN,
                    profileImageUrl = "https://example.com/admin-profile.jpg",
                ),
            )

        normalUser =
            userRepository.save(
                User(
                    name = "일반사용자",
                    email = "user-${UUID.randomUUID()}@example.com",
                    provider = OAuthProvider.GOOGLE,
                    identifier = "user-id-${UUID.randomUUID()}",
                    role = UserRole.USER,
                    profileImageUrl = "https://example.com/user-profile.jpg",
                ),
            )

        adminAccessToken = jwtTokenProvider.createAccessToken(adminUser.id!!, adminUser.role)
        userAccessToken = jwtTokenProvider.createAccessToken(normalUser.id!!, normalUser.role)
    }

    @Test
    @DisplayName("ADMIN 권한은 회사를 COMPANY 사용자로 등록할 수 있다")
    fun adminCanCreateCompanyUser() {
        given()
            .contentType("application/json")
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, adminAccessToken)
            .body(
                """
                {
                  "name": "토스",
                  "email": "contact@toss.im",
                  "profileImageUrl": "https://static.toss.im/logo.png"
                }
                """.trimIndent(),
            ).`when`()
            .post("/admin/companies")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .body("data.name", equalTo("토스"))
            .body("data.email", equalTo("contact@toss.im"))
            .body("data.profileImageUrl", equalTo("https://static.toss.im/logo.png"))

        val savedCompany =
            userRepository.findAll().first { it.name == "토스" }

        assertEquals(UserRole.COMPANY, savedCompany.role)
        assertEquals(OAuthProvider.SYSTEM, savedCompany.provider)
    }

    @Test
    @DisplayName("USER 권한은 회사 등록 API를 호출할 수 없다")
    fun userCannotCreateCompanyUser() {
        given()
            .contentType("application/json")
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, userAccessToken)
            .body(
                """
                {
                  "name": "토스",
                  "email": "contact@toss.im"
                }
                """.trimIndent(),
            ).`when`()
            .post("/admin/companies")
            .then()
            .statusCode(HttpStatus.FORBIDDEN.value())
    }

    @Test
    @DisplayName("ADMIN 권한은 등록된 회사 목록만 조회할 수 있다")
    fun adminCanGetCompanies() {
        userRepository.save(
            User(
                name = "당근",
                email = "hello@daangn.com",
                provider = OAuthProvider.SYSTEM,
                identifier = "company-daangn",
                role = UserRole.COMPANY,
                profileImageUrl = "https://example.com/daangn.png",
            ),
        )
        userRepository.save(
            User(
                name = "토스",
                email = "contact@toss.im",
                provider = OAuthProvider.SYSTEM,
                identifier = "company-toss",
                role = UserRole.COMPANY,
                profileImageUrl = "https://example.com/toss.png",
            ),
        )

        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, adminAccessToken)
            .`when`()
            .get("/admin/companies")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data", hasSize<Any>(2))
            .body("data[0].name", equalTo("당근"))
            .body("data[1].name", equalTo("토스"))
    }

    @Test
    @DisplayName("ADMIN 권한은 회사 봇 영구 토큰을 발급하고 DB에 저장할 수 있다")
    fun adminCanCreateCompanyPermanentToken() {
        // Given
        val companyUser = saveCompanyUser(name = "토스", identifier = "company-toss")

        // When
        val token =
            given()
                .contentType("application/json")
                .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, adminAccessToken)
                .body(
                    """
                    {
                      "name": "토스 기술블로그 수집 봇"
                    }
                    """.trimIndent(),
                ).`when`()
                .post("/admin/companies/${companyUser.id}/tokens")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("data.id", notNullValue())
                .body("data.userId", equalTo(companyUser.id.toString()))
                .body("data.name", equalTo("토스 기술블로그 수집 봇"))
                .body("data.token", notNullValue())
                .body("data.permanent", equalTo(true))
                .body("data.expiredAt", nullValue())
                .extract()
                .path<String>("data.token")

        // Then
        val savedToken = userTokenRepository.findAll().single()
        val payloadJson = decodeJwtPayload(token)

        assertEquals(companyUser.id, savedToken.user.id)
        assertEquals("토스 기술블로그 수집 봇", savedToken.name)
        assertEquals(jwtTokenProvider.hashToken(token), savedToken.tokenHash)
        assertTrue(payloadJson.contains("\"permanent\":true"))
        assertFalse(payloadJson.contains("\"exp\""))
    }

    @Test
    @DisplayName("회사 봇 영구 토큰을 재발급하면 기존 토큰은 삭제되고 새 토큰만 남는다")
    fun creatingCompanyPermanentTokenAgainReplacesExistingToken() {
        // Given
        val companyUser = saveCompanyUser(name = "토스", identifier = "company-toss")
        val firstToken = createCompanyToken(companyUser.id!!, "첫 번째 수집 봇")

        // When
        val secondToken = createCompanyToken(companyUser.id!!, "두 번째 수집 봇")

        // Then
        val savedToken = userTokenRepository.findAll().single()
        assertEquals(companyUser.id, savedToken.user.id)
        assertEquals("두 번째 수집 봇", savedToken.name)
        assertEquals(jwtTokenProvider.hashToken(secondToken), savedToken.tokenHash)
        assertFalse(firstToken == secondToken)

        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, firstToken)
            .`when`()
            .get("/api/users/me")
            .then()
            .statusCode(HttpStatus.UNAUTHORIZED.value())

        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, secondToken)
            .`when`()
            .get("/api/users/me")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.id", equalTo(companyUser.id.toString()))
    }

    @Test
    @DisplayName("DB에 저장된 회사 봇 영구 토큰은 사용자 API 인증에 사용할 수 있다")
    fun registeredCompanyPermanentTokenCanAuthenticate() {
        // Given
        val companyUser = saveCompanyUser(name = "토스", identifier = "company-toss")
        val token = createCompanyToken(companyUser.id!!)

        // When & Then
        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, token)
            .`when`()
            .get("/api/users/me")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.id", equalTo(companyUser.id.toString()))
            .body("data.name", equalTo("토스"))
    }

    @Test
    @DisplayName("ADMIN 권한은 회사 사용자를 삭제하며 관련 attachment, post, comment, link와 해당 link의 모든 user_links 관계를 삭제한다")
    fun adminCanDeleteCompanyUserWithOwnedContentAndLinks() {
        // Given
        val companyUser = saveCompanyUser(name = "토스", identifier = "company-toss")
        val otherCompanyUser = saveCompanyUser(name = "당근", identifier = "company-daangn")

        val companyProfileAttachment =
            saveAttachment(
                referenceId = companyUser.id!!,
                referenceType = AttachmentReferenceType.USER,
                objectKey = "users/${companyUser.id}/profile.png",
            )
        companyUser.serviceProfileImageAttachmentId = companyProfileAttachment.id
        userRepository.saveAndFlush(companyUser)

        val companyPost =
            postRepository.save(
                Post(
                    title = "회사 게시물",
                    content = "본문",
                    author = companyUser,
                ),
            )
        val companyPostAttachment =
            saveAttachment(
                referenceId = companyPost.id!!,
                referenceType = AttachmentReferenceType.POST,
                objectKey = "posts/${companyPost.id}/body.png",
            )
        companyPost.thumbnailImage = companyPostAttachment.id
        postRepository.saveAndFlush(companyPost)

        val otherUserPost =
            postRepository.save(
                Post(
                    title = "일반 사용자 게시물",
                    content = "본문",
                    author = normalUser,
                ),
            )

        val commentOnCompanyPost =
            commentRepository.save(
                Comment(
                    content = "회사 게시물의 댓글",
                    post = companyPost,
                    author = normalUser,
                ),
            )
        val companyCommentOnOtherPost =
            commentRepository.save(
                Comment(
                    content = "회사가 남긴 댓글",
                    post = otherUserPost,
                    author = companyUser,
                ),
            )

        val companyLink = saveLink("https://tech.example.com/${UUID.randomUUID()}")
        val companyUserLink = userLinkRepository.save(UserLink(companyUser, companyLink))
        val sharedUserLink = userLinkRepository.save(UserLink(otherCompanyUser, companyLink))

        val otherCompanyLink = saveLink("https://daangn.example.com/${UUID.randomUUID()}")
        val remainingUserLink = userLinkRepository.save(UserLink(otherCompanyUser, otherCompanyLink))

        // When
        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, adminAccessToken)
            .`when`()
            .delete("/admin/companies/${companyUser.id}")
            .then()
            .statusCode(HttpStatus.NO_CONTENT.value())

        // Then
        assertFalse(userRepository.existsById(companyUser.id!!))
        assertTrue(userRepository.existsById(normalUser.id!!))
        assertTrue(userRepository.existsById(otherCompanyUser.id!!))

        assertFalse(postRepository.existsById(companyPost.id!!))
        assertTrue(postRepository.existsById(otherUserPost.id!!))
        assertFalse(commentRepository.existsById(commentOnCompanyPost.id!!))
        assertFalse(commentRepository.existsById(companyCommentOnOtherPost.id!!))

        assertFalse(attachmentRepository.existsById(companyProfileAttachment.id!!))
        assertFalse(attachmentRepository.existsById(companyPostAttachment.id!!))

        assertFalse(linkRepository.existsById(companyLink.id!!))
        assertFalse(userLinkRepository.existsById(companyUserLink.id!!))
        assertFalse(userLinkRepository.existsById(sharedUserLink.id!!))
        assertTrue(linkRepository.existsById(otherCompanyLink.id!!))
        assertTrue(userLinkRepository.existsById(remainingUserLink.id!!))
    }

    @Test
    @DisplayName("회사 역할이 변경되면 저장된 영구 토큰이 삭제되어 재승격 후에도 인증에 실패한다")
    fun companyPermanentTokenCannotAuthenticateAfterRoleChanged() {
        // Given
        val companyUser = saveCompanyUser(name = "토스", identifier = "company-toss")
        val token = createCompanyToken(companyUser.id!!)
        assertEquals(1, userTokenRepository.count())

        // When
        updateUserRole(companyUser.id!!, UserRole.USER)
        updateUserRole(companyUser.id!!, UserRole.COMPANY)

        // Then
        assertEquals(0, userTokenRepository.count())
        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, token)
            .`when`()
            .get("/api/users/me")
            .then()
            .statusCode(HttpStatus.UNAUTHORIZED.value())
    }

    @Test
    @DisplayName("DB에 저장되지 않은 회사 봇 영구 토큰은 인증에 실패한다")
    fun unregisteredCompanyPermanentTokenCannotAuthenticate() {
        // Given
        val companyUser = saveCompanyUser(name = "토스", identifier = "company-toss")
        val token = createCompanyToken(companyUser.id!!)
        userTokenRepository.deleteAllInBatch()

        // When & Then
        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, token)
            .`when`()
            .get("/api/users/me")
            .then()
            .statusCode(HttpStatus.UNAUTHORIZED.value())
    }

    @Test
    @DisplayName("USER 권한은 회사 봇 영구 토큰을 발급할 수 없다")
    fun userCannotCreateCompanyPermanentToken() {
        // Given
        val companyUser = saveCompanyUser(name = "토스", identifier = "company-toss")

        // When & Then
        given()
            .contentType("application/json")
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, userAccessToken)
            .body(
                """
                {
                  "name": "토스 기술블로그 수집 봇"
                }
                """.trimIndent(),
            ).`when`()
            .post("/admin/companies/${companyUser.id}/tokens")
            .then()
            .statusCode(HttpStatus.FORBIDDEN.value())
    }

    private fun saveCompanyUser(
        name: String,
        identifier: String,
    ): User {
        return userRepository.save(
            User(
                name = name,
                email = "$identifier-${UUID.randomUUID()}@example.com",
                provider = OAuthProvider.SYSTEM,
                identifier = identifier,
                role = UserRole.COMPANY,
                profileImageUrl = "https://example.com/$identifier.png",
            ),
        )
    }

    private fun saveAttachment(
        referenceId: UUID,
        referenceType: AttachmentReferenceType,
        objectKey: String,
    ): Attachment {
        return attachmentRepository.save(
            Attachment(
                referenceId = referenceId,
                referenceType = referenceType,
                objectKey = objectKey,
                status = AttachmentStatus.CONFIRMED,
                originalFileName = objectKey.substringAfterLast("/"),
                contentType = "image/png",
                fileSize = 1024,
            ),
        )
    }

    private fun saveLink(url: String): Link {
        return linkRepository.save(
            Link(
                title = "기술 블로그",
                url = url,
                summary = "요약",
            ),
        )
    }

    private fun createCompanyToken(
        companyUserId: UUID,
        tokenName: String = "토스 기술블로그 수집 봇",
    ): String {
        return given()
            .contentType("application/json")
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, adminAccessToken)
            .body(
                """
                {
                  "name": "$tokenName"
                }
                """.trimIndent(),
            ).`when`()
            .post("/admin/companies/$companyUserId/tokens")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .extract()
            .path("data.token")
    }

    private fun updateUserRole(
        userId: UUID,
        role: UserRole,
    ) {
        given()
            .contentType("application/json")
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, adminAccessToken)
            .body(
                """
                {
                  "role": "${role.name}"
                }
                """.trimIndent(),
            ).`when`()
            .patch("/admin/users/$userId/role")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("data.role", equalTo(role.name))
    }

    private fun decodeJwtPayload(token: String): String {
        val payload = token.split(".")[1]
        return String(Base64.getUrlDecoder().decode(payload))
    }
}
