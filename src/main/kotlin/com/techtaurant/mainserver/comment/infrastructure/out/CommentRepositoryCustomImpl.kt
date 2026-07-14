package com.techtaurant.mainserver.comment.infrastructure.out

import com.techtaurant.mainserver.comment.dto.CommentCursor
import com.techtaurant.mainserver.comment.entity.Comment
import com.techtaurant.mainserver.comment.enums.CommentSortType
import com.techtaurant.mainserver.jooq.tables.Comments.Companion.COMMENTS
import com.techtaurant.mainserver.jooq.tables.Posts.Companion.POSTS
import com.techtaurant.mainserver.jooq.tables.Users.Companion.USERS
import com.techtaurant.mainserver.jooq.tables.records.PostsRecord
import com.techtaurant.mainserver.jooq.tables.records.UsersRecord
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import jakarta.persistence.EntityManager
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository
import java.util.Optional
import java.util.UUID

/**
 * 댓글 read model을 jOOQ로 조회한다.
 *
 * 반환 타입은 기존 서비스 계약을 유지하기 위해 엔티티를 사용하지만, 조회와 연관 데이터 조립은
 * 모두 하나의 jOOQ query 결과에서 수행한다.
 */
@Repository
class CommentRepositoryCustomImpl(
    private val dsl: DSLContext,
    private val entityManager: EntityManager,
) : CommentRepositoryCustom {
    override fun findByPostIdAndDeletedAtIsNullOrderByCreatedAtAsc(postId: UUID): List<Comment> =
        flushThen { fetchComments(COMMENTS.POST_ID.eq(postId).and(COMMENTS.DELETED_AT_UTC.isNull)) }
            .sortedBy { it.createdAt }

    override fun findById(id: UUID): Optional<Comment> = flushThen { Optional.ofNullable(fetchComments(COMMENTS.ID.eq(id)).firstOrNull()) }

    override fun existsById(id: UUID): Boolean = flushThen { dsl.fetchExists(COMMENTS, COMMENTS.ID.eq(id)) }

    override fun findCommentsByIdsIncludingDeleted(commentIds: List<UUID>): List<Comment> {
        if (commentIds.isEmpty()) {
            return emptyList()
        }

        return fetchComments(COMMENTS.ID.`in`(commentIds))
    }

    override fun findParentCommentsIncludingDeletedWithConditions(
        postId: UUID,
        cursor: CommentCursor?,
        size: Int,
        sortType: CommentSortType,
    ): List<Comment> =
        fetchComments(
            baseCondition = COMMENTS.POST_ID.eq(postId).and(COMMENTS.DEPTH.eq(0)),
            cursor = cursor,
            size = size,
            sortType = sortType,
        )

    override fun findRepliesIncludingDeletedWithConditions(
        parentId: UUID,
        cursor: CommentCursor?,
        size: Int,
        sortType: CommentSortType,
    ): List<Comment> =
        fetchComments(
            baseCondition = COMMENTS.PARENT_ID.eq(parentId).and(COMMENTS.DEPTH.eq(1)),
            cursor = cursor,
            size = size,
            sortType = sortType,
        )

    private fun fetchComments(baseCondition: Condition): List<Comment> = fetchCommentRows(baseCondition).map(::toComment)

    private fun fetchComments(
        baseCondition: Condition,
        cursor: CommentCursor?,
        size: Int,
        sortType: CommentSortType,
    ): List<Comment> {
        val cursorCondition = cursor?.let { buildCursorCondition(it, sortType) }
        val whereCondition = listOfNotNull(baseCondition, cursorCondition).reduce(Condition::and)

        return fetchCommentRows(whereCondition, size, sortType).map(::toComment)
    }

    private fun fetchCommentRows(baseCondition: Condition): List<Record> =
        dsl.select(COMMENTS.asterisk(), POSTS.asterisk(), USERS.asterisk())
            .from(COMMENTS)
            .join(POSTS).on(COMMENTS.POST_ID.eq(POSTS.ID))
            .join(USERS).on(COMMENTS.AUTHOR_ID.eq(USERS.ID))
            .where(baseCondition)
            .fetch()

    private fun fetchCommentRows(
        whereCondition: Condition,
        size: Int,
        sortType: CommentSortType,
    ): List<Record> {
        val query =
            dsl.select(COMMENTS.asterisk(), POSTS.asterisk(), USERS.asterisk())
                .from(COMMENTS)
                .join(POSTS).on(COMMENTS.POST_ID.eq(POSTS.ID))
                .join(USERS).on(COMMENTS.AUTHOR_ID.eq(USERS.ID))
                .where(whereCondition)

        return when (sortType) {
            CommentSortType.LATEST -> query.orderBy(COMMENTS.CREATED_AT_UTC.desc(), COMMENTS.ID.desc())
            CommentSortType.LIKE -> query.orderBy(COMMENTS.LIKE_COUNT.desc(), COMMENTS.CREATED_AT_UTC.desc(), COMMENTS.ID.desc())
            CommentSortType.REPLY -> query.orderBy(COMMENTS.REPLY_COUNT.desc(), COMMENTS.CREATED_AT_UTC.desc(), COMMENTS.ID.desc())
        }
            .limit(size)
            .fetch()
    }

    private fun buildCursorCondition(
        cursor: CommentCursor,
        sortType: CommentSortType,
    ): Condition {
        val cursorInstant = cursor.createdAt.atOffset(java.time.ZoneOffset.UTC)
        val timestampTieBreak = COMMENTS.CREATED_AT_UTC.eq(cursorInstant).and(COMMENTS.ID.lt(cursor.id))

        return when (sortType) {
            CommentSortType.LATEST -> COMMENTS.CREATED_AT_UTC.lt(cursorInstant).or(timestampTieBreak)
            CommentSortType.LIKE ->
                COMMENTS.LIKE_COUNT.lt(cursor.sortValue)
                    .or(COMMENTS.LIKE_COUNT.eq(cursor.sortValue).and(COMMENTS.CREATED_AT_UTC.lt(cursorInstant)))
                    .or(COMMENTS.LIKE_COUNT.eq(cursor.sortValue).and(timestampTieBreak))
            CommentSortType.REPLY ->
                COMMENTS.REPLY_COUNT.lt(cursor.sortValue)
                    .or(COMMENTS.REPLY_COUNT.eq(cursor.sortValue).and(COMMENTS.CREATED_AT_UTC.lt(cursorInstant)))
                    .or(COMMENTS.REPLY_COUNT.eq(cursor.sortValue).and(timestampTieBreak))
        }
    }

    private fun toComment(record: Record): Comment {
        val commentRecord = record.into(COMMENTS)
        val postRecord = record.into(POSTS)
        val author = record.into(USERS).toUser()
        val post = postRecord.toPost(author)

        return Comment(
            content = requireNotNull(commentRecord.content),
            post = post,
            author = author,
            parent = commentRecord.parentId?.let { parentId -> commentReference(parentId, post, author) },
            depth = requireNotNull(commentRecord.depth),
            likeCount = requireNotNull(commentRecord.likeCount),
            replyCount = requireNotNull(commentRecord.replyCount),
            deletedAt = commentRecord.deletedAtUtc?.toInstant(),
        ).apply {
            id = requireNotNull(commentRecord.id)
            createdAt = requireNotNull(commentRecord.createdAtUtc).toInstant()
            updatedAt = requireNotNull(commentRecord.updatedAtUtc).toInstant()
        }
    }

    private fun <T> flushThen(query: () -> T): T {
        if (entityManager.isJoinedToTransaction) {
            entityManager.flush()
        }
        return query()
    }

    private fun commentReference(
        parentId: UUID,
        post: Post,
        author: User,
    ): Comment = Comment(content = "", post = post, author = author).apply { id = parentId }

    private fun UsersRecord.toUser(): User =
        User(
            name = requireNotNull(name),
            email = requireNotNull(email),
            provider = OAuthProvider.valueOf(requireNotNull(provider)),
            identifier = requireNotNull(identifier),
            role = UserRole.valueOf(requireNotNull(role)),
            profileImageUrl = profileImageUrl.orEmpty(),
            serviceProfileImageAttachmentId = serviceProfileImageAttachmentId,
        ).apply {
            id = requireNotNull(this@toUser.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }

    private fun PostsRecord.toPost(author: User): Post =
        Post(
            title = requireNotNull(title),
            content = requireNotNull(content),
            author = author,
            viewCount = requireNotNull(viewCount),
            likeCount = requireNotNull(likeCount),
            commentCount = requireNotNull(commentCount),
            thumbnailImage = thumbnailImage,
            status = PostStatusEnum.valueOf(requireNotNull(status)),
        ).apply {
            id = requireNotNull(this@toPost.id)
            createdAt = requireNotNull(createdAtUtc).toInstant()
            updatedAt = requireNotNull(updatedAtUtc).toInstant()
        }
}
