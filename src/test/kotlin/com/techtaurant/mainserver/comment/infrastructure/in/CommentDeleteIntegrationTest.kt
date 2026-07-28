package com.techtaurant.mainserver.comment.infrastructure.`in`

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.comment.entity.Comment
import com.techtaurant.mainserver.comment.infrastructure.out.CommentRepository
import com.techtaurant.mainserver.common.util.DateUtils
import com.techtaurant.mainserver.post.entity.Category
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.infrastructure.out.CategoryRepository
import com.techtaurant.mainserver.post.infrastructure.out.PostDailyStatsRepository
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

@DisplayName("댓글 삭제 API 통합 테스트")
class CommentDeleteIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var postRepository: PostRepository

    @Autowired
    private lateinit var categoryRepository: CategoryRepository

    @Autowired
    private lateinit var commentRepository: CommentRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    private lateinit var postDailyStatsRepository: PostDailyStatsRepository

    private lateinit var author: User
    private lateinit var otherUser: User
    private lateinit var post: Post
    private lateinit var authorAccessToken: String
    private lateinit var otherUserAccessToken: String

    @BeforeEach
    fun setUpTestData() {
        author = createUser("author")
        otherUser = createUser("other")

        val category =
            categoryRepository.save(
                Category(
                    user = author,
                    name = "댓글테스트카테고리",
                    path = "댓글테스트카테고리-${UUID.randomUUID()}",
                    depth = 1,
                ),
            )

        post =
            postRepository.save(
                Post(
                    title = "댓글 삭제 테스트 게시물",
                    content = "테스트 게시물 내용",
                    author = author,
                    category = category,
                    commentCount = 1,
                ),
            )

        authorAccessToken = jwtTokenProvider.createAccessToken(author.id!!, author.role)
        otherUserAccessToken = jwtTokenProvider.createAccessToken(otherUser.id!!, otherUser.role)
    }

    @Test
    @DisplayName("댓글 삭제 성공 - 작성자가 삭제하면 내용이 SHA-256 해시로 치환된다")
    fun deleteComment_withAuthor_shouldSoftDeleteAndHashContent() {
        // given
        val originalContent = "삭제될 댓글 원문입니다"
        val comment = createComment(author, originalContent)

        // when
        given()
            .header("Authorization", "Bearer $authorAccessToken")
            .`when`()
            .delete("/api/comments/${comment.id}")
            .then()
            .statusCode(HttpStatus.NO_CONTENT.value())

        // then
        val deletedComment = commentRepository.findById(comment.id!!).orElseThrow()
        val updatedPost = postRepository.findById(post.id!!).orElseThrow()
        assertThat(deletedComment.deletedAt).isNotNull()
        assertThat(deletedComment.content).isEqualTo(sha256(originalContent))
        assertThat(updatedPost.commentCount).isZero()
        assertThat(commentRepository.findByPostIdAndDeletedAtIsNullOrderByCreatedAtAsc(post.id!!)).isEmpty()
    }

    @Test
    @DisplayName("대댓글 삭제 성공 - 부모 댓글 replyCount와 게시물 댓글수 및 일별 댓글 통계가 함께 감소한다")
    fun deleteReply_shouldDecreaseParentReplyCountAndDailyCommentCount() {
        // given
        val parentComment = createComment(author, "부모 댓글", replyCount = 1)
        val replyComment = createComment(author, "대댓글", parent = parentComment)
        val statDate = DateUtils.toUtcDate(replyComment.createdAt)
        post.commentCount = 2
        postRepository.save(post)

        // when
        given()
            .header("Authorization", "Bearer $authorAccessToken")
            .`when`()
            .delete("/api/comments/${replyComment.id}")
            .then()
            .statusCode(HttpStatus.NO_CONTENT.value())

        // then
        val updatedParent = commentRepository.findById(parentComment.id!!).orElseThrow()
        val updatedPost = postRepository.findById(post.id!!).orElseThrow()
        val dailyStats =
            postDailyStatsRepository.findAll()
                .single { it.post.id == post.id && it.statDate.toString() == statDate.toString() }
        assertThat(updatedParent.replyCount).isZero()
        assertThat(updatedPost.commentCount).isEqualTo(1)
        assertThat(dailyStats.commentCount).isEqualTo(-1)
    }

    @Test
    @DisplayName("대댓글 삭제 시 드리프트가 있어도 부모 replyCount와 게시물 commentCount는 0 미만으로 내려가지 않는다")
    fun deleteReply_shouldNotMakePublicCountersNegative() {
        // given
        val parentComment = createComment(author, "부모 댓글", replyCount = 0)
        val replyComment = createComment(author, "대댓글", parent = parentComment)
        val statDate = DateUtils.toUtcDate(replyComment.createdAt)
        post.commentCount = 0
        postRepository.save(post)

        // when
        given()
            .header("Authorization", "Bearer $authorAccessToken")
            .`when`()
            .delete("/api/comments/${replyComment.id}")
            .then()
            .statusCode(HttpStatus.NO_CONTENT.value())

        // then
        val updatedParent = commentRepository.findById(parentComment.id!!).orElseThrow()
        val updatedPost = postRepository.findById(post.id!!).orElseThrow()
        val dailyStats =
            postDailyStatsRepository.findAll()
                .single { it.post.id == post.id && it.statDate.toString() == statDate.toString() }
        assertThat(updatedParent.replyCount).isZero()
        assertThat(updatedPost.commentCount).isZero()
        assertThat(dailyStats.commentCount).isEqualTo(-1)
    }

    @Test
    @DisplayName("댓글 삭제 실패 - 작성자가 아니면 403과 COMMENT_AUTHOR_MISMATCH를 반환한다")
    fun deleteComment_withAnotherUser_shouldReturnForbidden() {
        // given
        val comment = createComment(author, "작성자만 삭제할 수 있는 댓글")

        // when
        given()
            .header("Authorization", "Bearer $otherUserAccessToken")
            .`when`()
            .delete("/api/comments/${comment.id}")
            .then()
            .statusCode(HttpStatus.FORBIDDEN.value())
            .body("status", org.hamcrest.Matchers.equalTo(7004))
            .body("message", org.hamcrest.Matchers.equalTo("댓글 작성자만 수행할 수 있습니다"))

        // then
        val persistedComment = commentRepository.findById(comment.id!!).orElseThrow()
        assertThat(persistedComment.deletedAt).isNull()
        assertThat(persistedComment.content).isEqualTo("작성자만 삭제할 수 있는 댓글")
    }

    @Test
    @DisplayName("댓글 삭제 실패 - 이미 삭제된 댓글이면 410과 COMMENT_ALREADY_DELETED를 반환한다")
    fun deleteComment_withAlreadyDeletedComment_shouldReturnGone() {
        // given
        val comment = createComment(author, "이미 삭제될 댓글")

        given()
            .header("Authorization", "Bearer $authorAccessToken")
            .`when`()
            .delete("/api/comments/${comment.id}")
            .then()
            .statusCode(HttpStatus.NO_CONTENT.value())

        // when
        given()
            .header("Authorization", "Bearer $authorAccessToken")
            .`when`()
            .delete("/api/comments/${comment.id}")
            .then()
            .statusCode(HttpStatus.GONE.value())
            .body("status", org.hamcrest.Matchers.equalTo(7005))
            .body("message", org.hamcrest.Matchers.equalTo("이미 삭제된 댓글입니다"))
    }

    @Test
    @DisplayName("댓글 삭제 실패 - 존재하지 않는 댓글이면 404와 COMMENT_NOT_FOUND를 반환한다")
    fun deleteComment_withNonExistentComment_shouldReturnNotFound() {
        // given
        val nonExistentCommentId = UUID.randomUUID()

        // when
        given()
            .header("Authorization", "Bearer $authorAccessToken")
            .`when`()
            .delete("/api/comments/$nonExistentCommentId")
            .then()
            .statusCode(HttpStatus.NOT_FOUND.value())
            .body("status", org.hamcrest.Matchers.equalTo(7001))
            .body("message", org.hamcrest.Matchers.equalTo("댓글을 찾을 수 없습니다"))
    }

    private fun createUser(identifierPrefix: String): User {
        return userRepository.save(
            User(
                name = "${identifierPrefix}사용자",
                email = "$identifierPrefix-${UUID.randomUUID()}@example.com",
                provider = OAuthProvider.GOOGLE,
                identifier = "$identifierPrefix-id-${UUID.randomUUID()}",
                role = UserRole.USER,
                profileImageUrl = "https://example.com/$identifierPrefix-profile.jpg",
            ),
        )
    }

    private fun createComment(
        author: User,
        content: String,
        parent: Comment? = null,
        replyCount: Long = 0,
    ): Comment {
        return commentRepository.save(
            Comment(
                content = content,
                post = post,
                author = author,
                parent = parent,
                depth = if (parent == null) 0 else 1,
                replyCount = replyCount,
            ),
        )
    }

    private fun sha256(content: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
