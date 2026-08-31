package com.techtaurant.mainserver.link.infrastructure.`in`

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.link.entity.Link
import com.techtaurant.mainserver.link.infrastructure.out.LinkLikeLogRepository
import com.techtaurant.mainserver.link.infrastructure.out.LinkReadLogRepository
import com.techtaurant.mainserver.link.infrastructure.out.LinkRepository
import com.techtaurant.mainserver.link.infrastructure.out.UserLinkRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.util.UUID

@DisplayName("LinkController 통합 테스트")
class LinkControllerIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var linkRepository: LinkRepository

    @Autowired
    private lateinit var userLinkRepository: UserLinkRepository

    @Autowired
    private lateinit var linkReadLogRepository: LinkReadLogRepository

    @Autowired
    private lateinit var linkLikeLogRepository: LinkLikeLogRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    private lateinit var normalUser: User
    private lateinit var accessToken: String
    private lateinit var firstLink: Link
    private lateinit var secondLink: Link

    @BeforeEach
    fun setUpTestData() {
        normalUser =
            userRepository.save(
                User(
                    name = "일반사용자",
                    email = "user-${UUID.randomUUID()}@example.com",
                    provider = OAuthProvider.GOOGLE,
                    identifier = "user-id-${UUID.randomUUID()}",
                    role = UserRole.USER,
                    profileImageUrl = "https://example.com/user.png",
                ),
            )

        accessToken = jwtTokenProvider.createAccessToken(normalUser.id!!, normalUser.role)

        firstLink =
            linkRepository.save(
                Link(
                    title = "Metric Review, 실행을 이끌다",
                    url = "https://toss.tech/article/metric-review",
                    summary = "지표 리뷰를 실행으로 연결한 사례입니다.",
                ),
            )

        secondLink =
            linkRepository.save(
                Link(
                    title = "StarRocks 운영기",
                    url = "https://toss.tech/article/starrocks",
                    summary = "멀티테넌트 워크로드 격리 전략을 소개합니다.",
                ),
            )
    }

    @Test
    @DisplayName("사용자는 링크를 저장하고 읽음 상태로 변경할 수 있다")
    fun userCanSaveAndMarkLinkAsRead() {
        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .`when`()
            .post("/api/links/${firstLink.id}/save")
            .then()
            .statusCode(HttpStatus.CREATED.value())

        assertNotNull(userLinkRepository.findByUserIdAndLinkId(normalUser.id!!, firstLink.id!!))

        given()
            .contentType("application/json")
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .body("""{"isRead": true}""")
            .`when`()
            .post("/api/links/${firstLink.id}/read-logs")
            .then()
            .statusCode(HttpStatus.OK.value())

        assertTrue(linkReadLogRepository.existsByUserIdAndLinkId(normalUser.id!!, firstLink.id!!))
    }

    @Test
    @DisplayName("사용자는 링크 저장을 취소하고 읽음 상태를 해제할 수 있다")
    fun userCanUnsaveAndUnreadLink() {
        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .`when`()
            .post("/api/links/${secondLink.id}/save")
            .then()
            .statusCode(HttpStatus.CREATED.value())

        given()
            .contentType("application/json")
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .body("""{"isRead": true}""")
            .`when`()
            .post("/api/links/${secondLink.id}/read-logs")
            .then()
            .statusCode(HttpStatus.OK.value())

        given()
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .`when`()
            .delete("/api/links/${secondLink.id}/save")
            .then()
            .statusCode(HttpStatus.NO_CONTENT.value())

        assertNull(userLinkRepository.findByUserIdAndLinkId(normalUser.id!!, secondLink.id!!))

        given()
            .contentType("application/json")
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .body("""{"isRead": false}""")
            .`when`()
            .post("/api/links/${secondLink.id}/read-logs")
            .then()
            .statusCode(HttpStatus.OK.value())

        assertFalse(linkReadLogRepository.existsByUserIdAndLinkId(normalUser.id!!, secondLink.id!!))
    }

    @Test
    @DisplayName("사용자는 링크 좋아요를 기록하고 조회 로그는 링크 조회수를 증가시킨다")
    fun userCanRecordLikeAndViewCountForLink() {
        given()
            .contentType("application/json")
            .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
            .body("""{"likeStatus": "LIKE"}""")
            .`when`()
            .post("/api/links/${firstLink.id}/like")
            .then()
            .statusCode(HttpStatus.OK.value())

        val likeLog = linkLikeLogRepository.findByLinkIdAndUserId(firstLink.id!!, normalUser.id!!)
        assertNotNull(likeLog)
        assertTrue(likeLog!!.isLiked)

        given()
            .header("User-Agent", "RestAssured")
            .`when`()
            .post("/open-api/links/${firstLink.id}/view-logs")
            .then()
            .statusCode(HttpStatus.OK.value())

        given()
            .header("User-Agent", "RestAssured")
            .`when`()
            .post("/open-api/links/${firstLink.id}/view-logs")
            .then()
            .statusCode(HttpStatus.OK.value())

        val link = linkRepository.findById(firstLink.id!!).orElseThrow()
        assertEquals(1, link.likeCount)
        assertEquals(2, link.viewCount)
    }
}
