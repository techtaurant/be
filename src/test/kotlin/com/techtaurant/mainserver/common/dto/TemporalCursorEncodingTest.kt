package com.techtaurant.mainserver.common.dto

import com.techtaurant.mainserver.comment.dto.CommentCursor
import com.techtaurant.mainserver.comment.enums.CommentSortType
import com.techtaurant.mainserver.link.dto.LinkCursor
import com.techtaurant.mainserver.link.enums.LinkSortType
import com.techtaurant.mainserver.notification.dto.NotificationCursor
import com.techtaurant.mainserver.post.dto.PostCursor
import com.techtaurant.mainserver.post.entity.PostSortType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64
import java.util.UUID

@DisplayName("UTC Instant cursor 인코딩 테스트")
class TemporalCursorEncodingTest {
    @Test
    @DisplayName("게시물 커서는 ISO-8601 UTC Instant를 보존하고 legacy epoch millis 커서도 읽는다")
    fun postCursor_preservesUtcInstantAndSupportsLegacyEpochMillis() {
        val id = UUID.randomUUID()
        val createdAt = Instant.parse("2026-03-01T23:59:59.123456Z")
        val cursor = PostCursor(sortValue = 42, createdAt = createdAt, id = id, sortType = PostSortType.COMMENT)

        val decoded = PostCursor.decode(cursor.encode())
        val legacyDecoded = PostCursor.decode(base64Url("${PostSortType.COMMENT.name}:42:${createdAt.toEpochMilli()}:$id"))

        assertThat(decoded).isEqualTo(cursor)
        assertThat(legacyDecoded).isEqualTo(cursor.copy(createdAt = Instant.ofEpochMilli(createdAt.toEpochMilli())))
    }

    @Test
    @DisplayName("댓글 커서는 ISO-8601 UTC Instant를 보존하고 legacy epoch millis 커서도 읽는다")
    fun commentCursor_preservesUtcInstantAndSupportsLegacyEpochMillis() {
        val id = UUID.randomUUID()
        val createdAt = Instant.parse("2026-04-02T01:02:03.456Z")
        val cursor = CommentCursor(sortValue = 7, createdAt = createdAt, id = id, sortType = CommentSortType.LIKE)

        val decoded = CommentCursor.decode(cursor.encode())
        val legacyDecoded = CommentCursor.decode(base64Url("${CommentSortType.LIKE.name}:7:${createdAt.toEpochMilli()}:$id"))

        assertThat(decoded).isEqualTo(cursor)
        assertThat(legacyDecoded).isEqualTo(cursor.copy(createdAt = Instant.ofEpochMilli(createdAt.toEpochMilli())))
    }

    @Test
    @DisplayName("알림 커서는 ISO-8601 UTC Instant를 보존하고 legacy epoch millis 커서도 읽는다")
    fun notificationCursor_preservesUtcInstantAndSupportsLegacyEpochMillis() {
        val id = UUID.randomUUID()
        val createdAt = Instant.parse("2026-05-03T04:05:06.789Z")
        val cursor = NotificationCursor(createdAt = createdAt, id = id)

        val decoded = NotificationCursor.decode(cursor.encode())
        val legacyDecoded = NotificationCursor.decode(base64Url("${createdAt.toEpochMilli()}:$id"))

        assertThat(decoded).isEqualTo(cursor)
        assertThat(legacyDecoded).isEqualTo(cursor.copy(createdAt = Instant.ofEpochMilli(createdAt.toEpochMilli())))
    }

    @Test
    @DisplayName("링크 커서는 정렬 타입과 생성일 UTC Instant를 보존한다")
    fun linkCursor_preservesSortTypeAndCreatedInstant() {
        val id = UUID.randomUUID()
        val createdAt = Instant.parse("2026-06-04T07:08:09.123Z")
        val cursor = LinkCursor(sortType = LinkSortType.PUBLISHED, sortValue = 0, sortInstant = createdAt, id = id)

        val decoded = LinkCursor.decode(cursor.encode())

        assertThat(decoded).isEqualTo(cursor)
    }

    @Test
    @DisplayName("링크 커서는 생성일이 없는 커서를 거부한다")
    fun linkCursor_rejectsNullCreatedInstant() {
        val id = UUID.randomUUID()
        val raw = base64Url("${LinkSortType.PUBLISHED.name}|0|null|$id")

        val decoded = LinkCursor.decode(raw)

        assertThat(decoded).isNull()
    }

    @Test
    @DisplayName("링크 좋아요/저장 커서는 집계 합과 createdAt을 보존한다")
    fun linkCursor_preservesAggregatedSortValueAndCreatedAt() {
        val id = UUID.randomUUID()
        val createdAt = Instant.parse("2026-05-03T04:05:06.789Z")
        val cursor = LinkCursor(sortType = LinkSortType.LIKE, sortValue = 42, sortInstant = createdAt, id = id)

        val decoded = LinkCursor.decode(cursor.encode())

        assertThat(decoded).isEqualTo(cursor)
    }

    private fun base64Url(raw: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())
}
