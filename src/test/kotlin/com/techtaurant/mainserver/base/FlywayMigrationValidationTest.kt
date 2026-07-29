package com.techtaurant.mainserver.base

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

@DisplayName("Flyway 마이그레이션 검증 테스트")
class FlywayMigrationValidationTest : IntegrationTest() {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    @DisplayName("전체 마이그레이션은 fresh PostgreSQL에 적용되고 링크 통계 마이그레이션 버전이 충돌하지 않는다")
    fun flywayMigrationsApplySuccessfullyWithoutLinkStatsVersionConflicts() {
        val failedMigrationCount =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false",
                Long::class.java,
            ) ?: 0L
        val appliedVersions =
            jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank",
                String::class.java,
            )

        assertThat(failedMigrationCount).isZero()
        assertThat(appliedVersions).doesNotHaveDuplicates()
        assertThat(appliedVersions).contains("32", "33", "35", "36", "37")
    }

    @Test
    @DisplayName("절대 시각 호환 컬럼은 timestamp with time zone 타입으로 생성된다")
    fun absoluteInstantCompatibilityColumnsUseTimestamptz() {
        val expectedColumns =
            entityBaseTables.flatMap { tableName ->
                listOf(
                    TemporalColumn(tableName, "created_at_utc"),
                    TemporalColumn(tableName, "updated_at_utc"),
                )
            } + extraInstantColumns

        val columnTypes =
            jdbcTemplate.query(
                """
                SELECT table_name, column_name, data_type
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND column_name = ANY (?::text[])
                """.trimIndent(),
                { rs, _ ->
                    TemporalColumn(rs.getString("table_name"), rs.getString("column_name")) to rs.getString("data_type")
                },
                expectedColumns.map { it.columnName }.distinct().toTypedArray(),
            ).toMap()

        assertThat(columnTypes.keys).containsExactlyInAnyOrderElementsOf(expectedColumns)
        assertThat(columnTypes.values).allSatisfy { dataType ->
            assertThat(dataType).isEqualTo("timestamp with time zone")
        }
    }

    @Test
    @DisplayName("절대 시각 sync trigger는 old TIMESTAMP와 new TIMESTAMPTZ 컬럼을 양방향 동기화한다")
    fun temporalSyncTriggersKeepOldAndUtcColumnsAligned() {
        val oldWriterTagId = UUID.randomUUID()
        val newWriterTagId = UUID.randomUUID()
        val linkId = UUID.randomUUID()

        jdbcTemplate.update(
            """
            INSERT INTO tags (id, name, created_at, updated_at)
            VALUES (?, ?, ?::timestamp, ?::timestamp)
            """.trimIndent(),
            oldWriterTagId,
            "old-writer-$oldWriterTagId",
            "2026-03-01 12:30:45",
            "2026-03-01 13:30:45",
        )

        jdbcTemplate.update(
            """
            INSERT INTO tags (id, name, created_at_utc, updated_at_utc)
            VALUES (?, ?, ?::timestamptz, ?::timestamptz)
            """.trimIndent(),
            newWriterTagId,
            "new-writer-$newWriterTagId",
            "2026-03-02 02:03:04Z",
            "2026-03-02 03:03:04Z",
        )

        jdbcTemplate.update(
            """
            INSERT INTO links (id, title, url, summary, published_at_utc)
            VALUES (?, ?, ?, ?, ?::timestamptz)
            """.trimIndent(),
            linkId,
            "UTC migration test link",
            "https://example.com/utc-migration-$linkId",
            "UTC migration summary",
            "2026-04-05 06:07:08Z",
        )

        jdbcTemplate.update(
            """
            UPDATE links
            SET published_at = ?::timestamp
            WHERE id = ?
            """.trimIndent(),
            "2026-04-06 07:08:09",
            linkId,
        )

        assertTemporalColumnsInSync("tags", oldWriterTagId, "created_at", "created_at_utc")
        assertTemporalColumnsInSync("tags", oldWriterTagId, "updated_at", "updated_at_utc")
        assertTemporalColumnsInSync("tags", newWriterTagId, "created_at", "created_at_utc")
        assertTemporalColumnsInSync("tags", newWriterTagId, "updated_at", "updated_at_utc")
        assertTemporalColumnsInSync("links", linkId, "published_at", "published_at_utc")
    }

    @Test
    @DisplayName("UTC 조회 경로 인덱스가 생성된다")
    fun utcTemporalReadPathIndexesExist() {
        val indexNames =
            jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'",
                String::class.java,
            )

        assertThat(indexNames).contains(
            "idx_posts_cursor_utc",
            "idx_posts_updated_at_utc",
            "idx_comments_created_at_utc",
            "idx_post_view_log_post_created_utc",
            "idx_post_like_log_post_created_utc",
            "idx_notification_recipients_user_id_created_at_utc",
            "idx_links_created_at_utc_id",
            "idx_links_published_at_utc",
            "idx_link_view_log_link_created_utc",
            "idx_link_like_log_link_created_utc",
        )
    }

    private fun assertTemporalColumnsInSync(
        tableName: String,
        id: UUID,
        oldColumnName: String,
        utcColumnName: String,
    ) {
        val inSync =
            jdbcTemplate.queryForObject(
                """
                SELECT ($oldColumnName IS NULL AND $utcColumnName IS NULL)
                    OR ($oldColumnName = $utcColumnName AT TIME ZONE 'UTC')
                FROM $tableName
                WHERE id = ?
                """.trimIndent(),
                Boolean::class.java,
                id,
            ) ?: false

        assertThat(inSync).isTrue()
    }

    private data class TemporalColumn(
        val tableName: String,
        val columnName: String,
    )

    companion object {
        private val entityBaseTables =
            listOf(
                "attachments",
                "categories",
                "comment_like_log",
                "comments",
                "link_crawl_batches",
                "link_crawl_failed_jobs",
                "link_crawl_runs",
                "link_daily_stats",
                "link_like_log",
                "link_read_log",
                "link_view_log",
                "links",
                "notification_arguments",
                "notification_recipients",
                "notifications",
                "post_daily_stats",
                "post_like_log",
                "post_read_log",
                "post_view_log",
                "posts",
                "tags",
                "user_bans",
                "user_follows",
                "user_links",
                "user_tokens",
                "users",
            )

        private val extraInstantColumns =
            listOf(
                TemporalColumn("comments", "deleted_at_utc"),
                TemporalColumn("link_crawl_batches", "last_triggered_at_utc"),
                TemporalColumn("link_crawl_failed_jobs", "last_failed_at_utc"),
                TemporalColumn("link_crawl_failed_jobs", "resolved_at_utc"),
                TemporalColumn("link_crawl_runs", "started_at_utc"),
                TemporalColumn("link_crawl_runs", "finished_at_utc"),
                TemporalColumn("links", "published_at_utc"),
                TemporalColumn("notification_recipients", "read_at_utc"),
                TemporalColumn("posts", "stats_updated_at_utc"),
            )
    }
}
