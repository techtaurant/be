package com.techtaurant.mainserver.comment.infrastructure.`in`

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.comment.dto.CommentListResponse
import com.techtaurant.mainserver.comment.entity.Comment
import com.techtaurant.mainserver.comment.entity.CommentLikeLog
import com.techtaurant.mainserver.comment.infrastructure.out.CommentLikeLogRepository
import com.techtaurant.mainserver.comment.infrastructure.out.CommentRepository
import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.dto.CursorPageResponse
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.entity.UserBan
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserBanRepository
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.restassured.RestAssured
import io.restassured.common.mapper.TypeRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Calendar
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisplayName("댓글 목록 조회 API")
class CommentReadControllerTest : IntegrationTest() {
    @Autowired
    private lateinit var commentRepository: CommentRepository

    @Autowired
    private lateinit var commentLikeLogRepository: CommentLikeLogRepository

    @Autowired
    private lateinit var postRepository: PostRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var userBanRepository: UserBanRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    private lateinit var testUser: User
    private lateinit var blockedUser: User
    private lateinit var testPost: Post
    private lateinit var parentComments: List<Comment>
    private lateinit var accessToken: String

    @BeforeEach
    fun setup() {
        userBanRepository.deleteAllInBatch()
        testUser =
            userRepository.save(
                User(
                    name = "Test User",
                    email = "test@example.com",
                    provider = OAuthProvider.GOOGLE,
                    identifier = "test-id-${UUID.randomUUID()}",
                    role = UserRole.USER,
                    profileImageUrl = "https://example.com/profile.jpg",
                ),
            )
        blockedUser =
            userRepository.save(
                User(
                    name = "Blocked User",
                    email = "blocked@example.com",
                    provider = OAuthProvider.GOOGLE,
                    identifier = "blocked-id-${UUID.randomUUID()}",
                    role = UserRole.USER,
                    profileImageUrl = "https://example.com/blocked-profile.jpg",
                ),
            )
        accessToken = jwtTokenProvider.createAccessToken(testUser.id!!, testUser.role)

        // Given: 테스트 게시물 생성
        testPost =
            postRepository.save(
                Post(
                    title = "Test Post",
                    content = "Test Content",
                    author = testUser,
                ),
            )

        // Given: 다양한 통계를 가진 테스트 댓글 생성
        parentComments = createTestComments()
    }

    @Nested
    @DisplayName("부모 댓글 정렬 기준별 검증")
    inner class ParentCommentSortTypeTest {
        @Test
        @DisplayName("LATEST - 최신순으로 정렬된 부모 댓글이 로딩되어야 한다")
        fun whenSortByLatest_thenCommentsOrderedByCreatedAtDesc() {
            // When: 최신순 조회
            val response =
                RestAssured
                    .given()
                    .queryParam("sort", "LATEST")
                    .queryParam("size", 10)
                    .`when`()
                    .get("/open-api/comments/posts/${testPost.id}")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<CommentListResponse>>>() {})

            // Then: 최신 댓글부터 순서대로 정렬되어 있어야 함
            val content = response.data!!.content
            assertEquals(3, content.size)

            // 가장 최신 댓글이 먼저 와야 함 (1일 전 생성된 댓글)
            assertEquals(parentComments[2].id, content[0].id, "가장 최신 댓글이 첫 번째여야 함")
            assertEquals(parentComments[1].id, content[1].id)
            assertEquals(parentComments[0].id, content[2].id, "가장 오래된 댓글이 마지막이어야 함")
        }

        @Test
        @DisplayName("LIKE - 좋아요수 기준으로 정렬된 부모 댓글이 로딩되어야 한다")
        fun whenSortByLike_thenCommentsOrderedByLikeCountDesc() {
            // When: 좋아요수 기준 정렬 조회
            val response =
                RestAssured
                    .given()
                    .queryParam("sort", "LIKE")
                    .queryParam("size", 10)
                    .`when`()
                    .get("/open-api/comments/posts/${testPost.id}")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<CommentListResponse>>>() {})

            // Then: 좋아요수가 높은 순서대로 정렬되어 있어야 함
            val content = response.data!!.content
            assertEquals(3, content.size)

            // 좋아요수가 내림차순으로 정렬되어야 함
            assertTrue(content[0].likeCount >= content[1].likeCount, "좋아요수 기준 내림차순 정렬되어야 함")
            assertTrue(content[1].likeCount >= content[2].likeCount, "좋아요수 기준 내림차순 정렬되어야 함")

            // 좋아요수가 50인 댓글이 가장 먼저 와야 함
            assertEquals(50L, content[0].likeCount, "좋아요수 50인 댓글이 첫 번째여야 함")
            assertEquals(25L, content[1].likeCount)
            assertEquals(5L, content[2].likeCount)
        }

        @Test
        @DisplayName("REPLY - 답글수 기준으로 정렬된 부모 댓글이 로딩되어야 한다")
        fun whenSortByReply_thenCommentsOrderedByReplyCountDesc() {
            // When: 답글수 기준 정렬 조회
            val response =
                RestAssured
                    .given()
                    .queryParam("sort", "REPLY")
                    .queryParam("size", 10)
                    .`when`()
                    .get("/open-api/comments/posts/${testPost.id}")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<CommentListResponse>>>() {})

            // Then: 답글수가 높은 순서대로 정렬되어 있어야 함
            val content = response.data!!.content
            assertEquals(3, content.size)

            // 답글수가 내림차순으로 정렬되어야 함
            assertTrue(content[0].replyCount >= content[1].replyCount, "답글수 기준 내림차순 정렬되어야 함")
            assertTrue(content[1].replyCount >= content[2].replyCount, "답글수 기준 내림차순 정렬되어야 함")

            // 답글수가 20인 댓글이 가장 먼저 와야 함
            assertEquals(20L, content[0].replyCount, "답글수 20인 댓글이 첫 번째여야 함")
            assertEquals(10L, content[1].replyCount)
            assertEquals(2L, content[2].replyCount)
        }
    }

    @Nested
    @DisplayName("대댓글 정렬 기준별 검증")
    inner class ReplySortTypeTest {
        private lateinit var parentComment: Comment
        private lateinit var replies: List<Comment>

        @BeforeEach
        fun setupReplies() {
            // Given: 대댓글 테스트용 부모 댓글 가져오기
            parentComment = parentComments[0]

            // Given: 다양한 통계를 가진 대댓글 생성
            replies = createTestReplies(parentComment)
        }

        @Test
        @DisplayName("LATEST - 최신순으로 정렬된 대댓글이 로딩되어야 한다")
        fun whenSortByLatest_thenRepliesOrderedByCreatedAtDesc() {
            // When: 최신순 조회
            val response =
                RestAssured
                    .given()
                    .queryParam("sort", "LATEST")
                    .queryParam("size", 10)
                    .`when`()
                    .get("/open-api/comments/${parentComment.id}/replies")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<CommentListResponse>>>() {})

            // Then: 최신 대댓글부터 순서대로 정렬되어 있어야 함
            val content = response.data!!.content
            assertEquals(3, content.size)

            // 가장 최신 대댓글이 먼저 와야 함
            assertEquals(replies[2].id, content[0].id, "가장 최신 대댓글이 첫 번째여야 함")
            assertEquals(replies[1].id, content[1].id)
            assertEquals(replies[0].id, content[2].id, "가장 오래된 대댓글이 마지막이어야 함")
        }

        @Test
        @DisplayName("LIKE - 좋아요수 기준으로 정렬된 대댓글이 로딩되어야 한다")
        fun whenSortByLike_thenRepliesOrderedByLikeCountDesc() {
            // When: 좋아요수 기준 정렬 조회
            val response =
                RestAssured
                    .given()
                    .queryParam("sort", "LIKE")
                    .queryParam("size", 10)
                    .`when`()
                    .get("/open-api/comments/${parentComment.id}/replies")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<CommentListResponse>>>() {})

            // Then: 좋아요수가 높은 순서대로 정렬되어 있어야 함
            val content = response.data!!.content
            assertEquals(3, content.size)

            // 좋아요수가 내림차순으로 정렬되어야 함
            assertTrue(content[0].likeCount >= content[1].likeCount, "좋아요수 기준 내림차순 정렬되어야 함")
            assertTrue(content[1].likeCount >= content[2].likeCount, "좋아요수 기준 내림차순 정렬되어야 함")
        }
    }

    @Nested
    @DisplayName("페이지네이션 검증")
    inner class PaginationTest {
        @Test
        @DisplayName("첫 페이지 조회 시 nextCursor가 반환되어야 한다")
        fun whenGetFirstPage_thenNextCursorReturned() {
            // When: 첫 페이지 조회 (size=1로 다음 페이지가 있도록 설정)
            val response =
                RestAssured
                    .given()
                    .queryParam("size", 1)
                    .`when`()
                    .get("/open-api/comments/posts/${testPost.id}")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<CommentListResponse>>>() {})

            // Then: 다음 페이지가 있으면 nextCursor가 반환되어야 함
            assertTrue(response.data!!.hasNext, "다음 페이지가 있어야 함")
            assertNotNull(response.data!!.nextCursor, "nextCursor가 반환되어야 함")
            assertEquals(1, response.data!!.size)
        }

        @Test
        @DisplayName("마지막 페이지 조회 시 nextCursor가 null이어야 한다")
        fun whenGetLastPage_thenNextCursorIsNull() {
            // When: 충분한 크기로 모든 데이터를 한 번에 조회
            val response =
                RestAssured
                    .given()
                    .queryParam("size", 100)
                    .`when`()
                    .get("/open-api/comments/posts/${testPost.id}")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<CommentListResponse>>>() {})

            // Then: 마지막 페이지이므로 nextCursor가 null이어야 함
            assertTrue(!response.data!!.hasNext, "다음 페이지가 없어야 함")
            assertEquals(null, response.data!!.nextCursor, "nextCursor가 null이어야 함")
            assertEquals(3, response.data!!.size)
        }

        @Test
        @DisplayName("커서를 이용한 다음 페이지 조회가 정확해야 한다")
        fun whenGetCommentsWithCursor_thenNextPageLoaded() {
            // When: 첫 페이지 조회
            val firstPageResponse =
                RestAssured
                    .given()
                    .queryParam("size", 1)
                    .`when`()
                    .get("/open-api/comments/posts/${testPost.id}")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<CommentListResponse>>>() {})

            val firstPageCommentId = firstPageResponse.data!!.content[0].id
            val cursor = firstPageResponse.data!!.nextCursor

            // When: 커서를 이용해 다음 페이지 조회
            val secondPageResponse =
                RestAssured
                    .given()
                    .queryParam("cursor", cursor)
                    .queryParam("size", 1)
                    .`when`()
                    .get("/open-api/comments/posts/${testPost.id}")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<CommentListResponse>>>() {})

            // Then: 다음 페이지의 댓글이 첫 번째와 달라야 함
            val secondPageCommentId = secondPageResponse.data!!.content[0].id
            assertEquals(1, secondPageResponse.data!!.size)
            assertTrue(firstPageCommentId != secondPageCommentId, "다음 페이지의 댓글이 달라야 함")
        }
    }

    @Test
    @DisplayName("로그인 사용자가 차단한 작성자의 댓글은 마스킹되고 isBanned=true로 반환된다")
    fun getParentComments_masksBannedAuthorComment() {
        // Given
        val blockedComment =
            commentRepository.save(
                Comment(
                    content = "차단된 댓글 내용",
                    post = testPost,
                    author = blockedUser,
                    parent = null,
                    depth = 0,
                ),
            )
        userBanRepository.save(UserBan(user = testUser, bannedUser = blockedUser))

        // When
        val response =
            RestAssured
                .given()
                .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
                .queryParam("sort", "LATEST")
                .queryParam("size", 10)
                .`when`()
                .get("/open-api/comments/posts/${testPost.id}")
                .then()
                .statusCode(200)
                .extract()
                .`as`(object : TypeRef<ApiResponse<CursorPageResponse<CommentListResponse>>>() {})

        // Then
        val maskedComment = response.data!!.content.first { it.id == blockedComment.id }
        assertTrue(maskedComment.isBanned)
        assertTrue(maskedComment.authorName.startsWith("banned_"))
        assertEquals(13, maskedComment.authorName.length)
        assertTrue(maskedComment.content.startsWith("banned_"))
        assertEquals(13, maskedComment.content.length)
        assertEquals(null, maskedComment.authorProfileImageUrl)
        assertTrue(maskedComment.authorId != blockedUser.id)
    }

    @Test
    @DisplayName("v2 부모 댓글 목록은 공개 콘텐츠만 반환하고 사용자 상태와 동적 메타데이터를 제외한다")
    fun getParentCommentContents_excludesViewerStateAndMetadataFields() {
        // given
        val deletedComment =
            parentComments[0].apply {
                content = sha256(content)
                deletedAt = Instant.now()
            }
        commentRepository.save(deletedComment)

        // when
        val response =
            RestAssured
                .given()
                .queryParam("size", 10)
                .`when`()
                .get("/open-api/v2/posts/${testPost.id}/comments")
                .then()
                .statusCode(200)
                .extract()
                .response()

        // then
        val firstComment = response.jsonPath().getMap<String, Any?>("data.content[0]")
        assertThat(firstComment.keys)
            .contains(
                "id",
                "content",
                "postId",
                "authorId",
                "parentId",
                "depth",
                "createdAt",
                "updatedAt",
            )
        assertThat(firstComment.keys)
            .doesNotContain("authorName", "authorProfileImageUrl", "likeCount", "replyCount", "isDeleted", "likeStatus", "isBanned")
    }

    @Test
    @DisplayName("v2 부모 댓글 목록은 잘못된 커서가 전달되면 빈 페이지를 반환한다")
    fun getParentCommentContents_withMalformedCursor_returnsEmptyPage() {
        // when
        val response =
            RestAssured
                .given()
                .queryParam("cursor", "not-base64-cursor")
                .queryParam("size", 10)
                .`when`()
                .get("/open-api/v2/posts/${testPost.id}/comments")
                .then()
                .statusCode(200)
                .extract()
                .response()

        // then
        assertThat(response.jsonPath().getList<Any>("data.content")).isEmpty()
        assertThat(response.jsonPath().getBoolean("data.hasNext")).isFalse()
        assertThat(response.jsonPath().getInt("data.size")).isZero()
    }

    @Test
    @DisplayName("v2 대댓글 목록은 잘못된 커서가 전달되면 빈 페이지를 반환한다")
    fun getReplyContents_withMalformedCursor_returnsEmptyPage() {
        // given
        val parentComment = parentComments[0]
        createTestReplies(parentComment)

        // when
        val response =
            RestAssured
                .given()
                .queryParam("cursor", "not-base64-cursor")
                .queryParam("size", 10)
                .`when`()
                .get("/open-api/v2/comments/${parentComment.id}/replies")
                .then()
                .statusCode(200)
                .extract()
                .response()

        // then
        assertThat(response.jsonPath().getList<Any>("data.content")).isEmpty()
        assertThat(response.jsonPath().getBoolean("data.hasNext")).isFalse()
        assertThat(response.jsonPath().getInt("data.size")).isZero()
    }

    @Test
    @DisplayName("댓글 metadatas는 좋아요수, 대댓글수, 삭제 여부를 댓글 ID 순서대로 반환한다")
    fun getCommentMetadata_returnsLikeCountAndDeletedStateInRequestOrder() {
        // given
        val deletedComment =
            parentComments[0].apply {
                content = sha256(content)
                deletedAt = Instant.now()
            }
        commentRepository.save(deletedComment)
        val activeComment = parentComments[2]

        // when
        val response =
            RestAssured
                .given()
                .queryParam("commentIds", activeComment.id, deletedComment.id)
                .`when`()
                .get("/open-api/comments/metadatas")
                .then()
                .statusCode(200)
                .extract()
                .response()

        // then
        assertThat(response.jsonPath().getList<String>("data.commentId"))
            .containsExactly(activeComment.id.toString(), deletedComment.id.toString())
        assertThat(response.jsonPath().getInt("data.find { it.commentId == '${activeComment.id}' }.likeCount"))
            .isEqualTo(activeComment.likeCount.toInt())
        assertThat(response.jsonPath().getInt("data.find { it.commentId == '${activeComment.id}' }.replyCount"))
            .isEqualTo(activeComment.replyCount.toInt())
        assertThat(response.jsonPath().getBoolean("data.find { it.commentId == '${activeComment.id}' }.isDeleted"))
            .isFalse()
        assertThat(response.jsonPath().getInt("data.find { it.commentId == '${deletedComment.id}' }.likeCount"))
            .isEqualTo(deletedComment.likeCount.toInt())
        assertThat(response.jsonPath().getInt("data.find { it.commentId == '${deletedComment.id}' }.replyCount"))
            .isEqualTo(deletedComment.replyCount.toInt())
        assertThat(response.jsonPath().getBoolean("data.find { it.commentId == '${deletedComment.id}' }.isDeleted"))
            .isTrue()
    }

    @Test
    @DisplayName("댓글 사용자 상태는 좋아요 상태와 차단 여부를 댓글 ID 순서대로 반환한다")
    fun getCommentViewerStates_returnsLikeStatusAndBanStateInRequestOrder() {
        // given
        val likedComment = parentComments[0]
        val blockedComment =
            commentRepository.save(
                Comment(
                    content = "차단된 댓글 내용",
                    post = testPost,
                    author = blockedUser,
                    parent = null,
                    depth = 0,
                ),
            )
        commentLikeLogRepository.save(CommentLikeLog(comment = likedComment, user = testUser, isLiked = true))
        userBanRepository.save(UserBan(user = testUser, bannedUser = blockedUser))

        // when
        val response =
            RestAssured
                .given()
                .cookie(JwtConstants.ACCESS_TOKEN_COOKIE, accessToken)
                .queryParam("commentIds", blockedComment.id, likedComment.id)
                .`when`()
                .get("/api/comments/me/states")
                .then()
                .statusCode(200)
                .extract()
                .response()

        // then
        assertThat(response.jsonPath().getList<String>("data.commentId"))
            .containsExactly(blockedComment.id.toString(), likedComment.id.toString())
        assertThat(response.jsonPath().getString("data.find { it.commentId == '${blockedComment.id}' }.likeStatus"))
            .isEqualTo("NONE")
        assertThat(response.jsonPath().getBoolean("data.find { it.commentId == '${blockedComment.id}' }.isBanned"))
            .isTrue()
        assertThat(response.jsonPath().getString("data.find { it.commentId == '${likedComment.id}' }.likeStatus"))
            .isEqualTo("LIKE")
        assertThat(response.jsonPath().getBoolean("data.find { it.commentId == '${likedComment.id}' }.isBanned"))
            .isFalse()
    }

    @Nested
    @DisplayName("기본 댓글 조회 검증")
    inner class BasicCommentLoadingTest {
        @Test
        @DisplayName("댓글 기본 정보가 올바르게 로딩되어야 한다")
        fun whenGetComments_thenCommentDataLoadedCorrectly() {
            // When: 댓글 목록 조회
            val response =
                RestAssured
                    .given()
                    .queryParam("size", 10)
                    .`when`()
                    .get("/open-api/comments/posts/${testPost.id}")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<CommentListResponse>>>() {})

            // Then: 댓글의 기본 정보가 올바르게 로드되어야 함
            val content = response.data!!.content
            val firstComment = content[0]

            assertNotNull(firstComment.id)
            assertNotNull(firstComment.content)
            assertNotNull(firstComment.authorName)
            assertNotNull(firstComment.createdAt)
            assertEquals("Test User", firstComment.authorName, "작성자 이름이 올바르게 로드되어야 함")
            assertEquals(0, firstComment.depth, "부모 댓글의 depth는 0이어야 함")
        }

        @Test
        @DisplayName("통계 정보(좋아요, 답글)가 올바르게 로딩되어야 한다")
        fun whenGetComments_thenStatsDataLoadedCorrectly() {
            // When: 댓글 목록 조회
            val response =
                RestAssured
                    .given()
                    .queryParam("size", 10)
                    .`when`()
                    .get("/open-api/comments/posts/${testPost.id}")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<CommentListResponse>>>() {})

            // Then: 통계 정보가 올바르게 로드되어야 함
            val content = response.data!!.content
            content.forEach { comment ->
                assertTrue(comment.likeCount >= 0, "좋아요수는 음수가 아니어야 함")
                assertTrue(comment.replyCount >= 0, "답글수는 음수가 아니어야 함")
            }
        }

        @Test
        @DisplayName("삭제된 부모 댓글도 목록 조회 결과에 포함되어야 한다")
        fun whenGetParentComments_thenDeletedCommentIncluded() {
            // given
            val deletedComment =
                parentComments[0].apply {
                    content = sha256(content)
                    deletedAt = Instant.now()
                }
            commentRepository.save(deletedComment)

            // when
            val response =
                RestAssured
                    .given()
                    .queryParam("size", 10)
                    .`when`()
                    .get("/open-api/comments/posts/${testPost.id}")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<CommentListResponse>>>() {})

            // then
            val content = response.data!!.content
            assertEquals(3, content.size)
            val foundDeletedComment = content.find { it.id == deletedComment.id }
            assertNotNull(foundDeletedComment, "삭제된 댓글도 조회 결과에 포함되어야 함")
            assertTrue(foundDeletedComment.isDeleted, "삭제된 댓글은 isDeleted=true여야 함")
            assertEquals(deletedComment.content, foundDeletedComment.content)
        }

        @Test
        @DisplayName("삭제된 대댓글도 목록 조회 결과에 포함되어야 한다")
        fun whenGetReplies_thenDeletedReplyIncluded() {
            // given
            val parentComment = parentComments[0]
            val replies = createTestReplies(parentComment)
            val deletedReply =
                replies[0].apply {
                    content = sha256(content)
                    deletedAt = Instant.now()
                }
            commentRepository.save(deletedReply)

            // when
            val response =
                RestAssured
                    .given()
                    .queryParam("size", 10)
                    .`when`()
                    .get("/open-api/comments/${parentComment.id}/replies")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(object : TypeRef<ApiResponse<CursorPageResponse<CommentListResponse>>>() {})

            // then
            val content = response.data!!.content
            assertEquals(3, content.size)
            val foundDeletedReply = content.find { it.id == deletedReply.id }
            assertNotNull(foundDeletedReply, "삭제된 대댓글도 조회 결과에 포함되어야 함")
            assertTrue(foundDeletedReply.isDeleted, "삭제된 대댓글은 isDeleted=true여야 함")
            assertEquals(deletedReply.content, foundDeletedReply.content)
        }
    }

    /**
     * 테스트용 부모 댓글 생성 헬퍼 메서드
     *
     * 3개의 댓글을 생성하되, 각각 다른 통계값과 생성 시간을 가짐
     * - 댓글 1: 10일 전, 좋아요 5, 답글 2
     * - 댓글 2: 5일 전, 좋아요 25, 답글 10
     * - 댓글 3: 1일 전, 좋아요 50, 답글 20
     */
    private fun createTestComments(): List<Comment> {
        // 댓글 1: 10일 전
        val comment1CreatedAt =
            Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -10)
            }.toInstant()
        val comment1 =
            Comment(
                content = "Comment 1 - 10 days ago",
                post = testPost,
                author = testUser,
                depth = 0,
                likeCount = 5,
                replyCount = 2,
            ).apply {
                createdAt = comment1CreatedAt
            }

        // 댓글 2: 5일 전
        val comment2CreatedAt =
            Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -5)
            }.toInstant()
        val comment2 =
            Comment(
                content = "Comment 2 - 5 days ago",
                post = testPost,
                author = testUser,
                depth = 0,
                likeCount = 25,
                replyCount = 10,
            ).apply {
                createdAt = comment2CreatedAt
            }

        // 댓글 3: 1일 전
        val comment3CreatedAt =
            Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -1)
            }.toInstant()
        val comment3 =
            Comment(
                content = "Comment 3 - 1 day ago",
                post = testPost,
                author = testUser,
                depth = 0,
                likeCount = 50,
                replyCount = 20,
            ).apply {
                createdAt = comment3CreatedAt
            }

        return commentRepository.saveAll(listOf(comment1, comment2, comment3))
    }

    /**
     * 테스트용 대댓글 생성 헬퍼 메서드
     *
     * 3개의 대댓글을 생성하되, 각각 다른 통계값과 생성 시간을 가짐
     * - 대댓글 1: 9일 전, 좋아요 3, 답글 0
     * - 대댓글 2: 4일 전, 좋아요 15, 답글 0
     * - 대댓글 3: 12시간 전, 좋아요 30, 답글 0
     */
    private fun createTestReplies(parent: Comment): List<Comment> {
        // 대댓글 1: 9일 전
        val reply1CreatedAt =
            Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -9)
            }.toInstant()
        val reply1 =
            Comment(
                content = "Reply 1 - 9 days ago",
                post = testPost,
                author = testUser,
                parent = parent,
                depth = 1,
                likeCount = 3,
                replyCount = 0,
            ).apply {
                createdAt = reply1CreatedAt
            }

        // 대댓글 2: 4일 전
        val reply2CreatedAt =
            Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -4)
            }.toInstant()
        val reply2 =
            Comment(
                content = "Reply 2 - 4 days ago",
                post = testPost,
                author = testUser,
                parent = parent,
                depth = 1,
                likeCount = 15,
                replyCount = 0,
            ).apply {
                createdAt = reply2CreatedAt
            }

        // 대댓글 3: 12시간 전
        val reply3CreatedAt =
            Calendar.getInstance().apply {
                add(Calendar.HOUR_OF_DAY, -12)
            }.toInstant()
        val reply3 =
            Comment(
                content = "Reply 3 - 12 hours ago",
                post = testPost,
                author = testUser,
                parent = parent,
                depth = 1,
                likeCount = 30,
                replyCount = 0,
            ).apply {
                createdAt = reply3CreatedAt
            }

        return commentRepository.saveAll(listOf(reply1, reply2, reply3))
    }

    private fun sha256(content: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
