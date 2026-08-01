package com.techtaurant.mainserver.comment.infrastructure.out

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.comment.entity.Comment
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Transactional
@DisplayName("CommentRepositoryCustomImpl 통합 테스트")
class CommentRepositoryCustomImplTest : IntegrationTest() {
    @Autowired
    private lateinit var commentRepository: CommentRepository

    @Autowired
    private lateinit var postRepository: PostRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    private lateinit var author: User
    private lateinit var post: Post

    @BeforeEach
    fun setUpTestData() {
        author =
            userRepository.save(
                User(
                    name = "작성자",
                    email = "comment-author-${UUID.randomUUID()}@example.com",
                    provider = OAuthProvider.GOOGLE,
                    identifier = "comment-author-${UUID.randomUUID()}",
                    role = UserRole.USER,
                    profileImageUrl = "https://example.com/author.jpg",
                ),
            )

        post =
            postRepository.save(
                Post(
                    title = "댓글 카운터 테스트 게시물",
                    content = "본문입니다.",
                    author = author,
                ),
            )
    }

    @Test
    @DisplayName("save는 조회 이후 원자적으로 증가한 좋아요수/대댓글수를 덮어쓰지 않는다")
    fun save_doesNotOverwriteAtomicallyIncrementedCounters() {
        // given
        val commentId = createComment().id!!
        val staleComment = commentRepository.findById(commentId).orElseThrow()

        commentRepository.incrementLikeCount(commentId)
        commentRepository.incrementReplyCount(commentId)

        // when
        staleComment.content = "수정된 댓글"
        commentRepository.save(staleComment)

        // then
        val reloadedComment = commentRepository.findById(commentId).orElseThrow()
        assertThat(reloadedComment.content).isEqualTo("수정된 댓글")
        assertThat(reloadedComment.likeCount).isEqualTo(1L)
        assertThat(reloadedComment.replyCount).isEqualTo(1L)
    }

    @Test
    @DisplayName("소프트 삭제 저장도 원자적으로 증가한 좋아요수를 덮어쓰지 않는다")
    fun save_softDeleteDoesNotOverwriteAtomicallyIncrementedLikeCount() {
        // given
        val commentId = createComment().id!!
        val staleComment = commentRepository.findById(commentId).orElseThrow()

        commentRepository.incrementLikeCount(commentId)

        // when
        staleComment.deletedAt = Instant.now()
        commentRepository.save(staleComment)

        // then
        val reloadedComment = commentRepository.findById(commentId).orElseThrow()
        assertThat(reloadedComment.deletedAt).isNotNull()
        assertThat(reloadedComment.likeCount).isEqualTo(1L)
    }

    private fun createComment(): Comment =
        commentRepository.save(
            Comment(
                content = "원본 댓글",
                post = post,
                author = author,
            ),
        )
}
