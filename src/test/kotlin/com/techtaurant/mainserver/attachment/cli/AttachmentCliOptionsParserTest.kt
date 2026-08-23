package com.techtaurant.mainserver.attachment.cli

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Attachment CLI 옵션 파서")
class AttachmentCliOptionsParserTest {
    @Test
    @DisplayName("verify는 기본 배치 크기 500을 사용한다")
    fun parseVerifyWithDefaultBatchSize() {
        val options = AttachmentCliOptionsParser.parse(listOf("verify"))

        assertThat(options).isEqualTo(AttachmentCliOptions.Verify(batchSize = 500))
    }

    @Test
    @DisplayName("orphan 삭제에는 버킷 확인값이 필요하다")
    fun requireBucketConfirmationForDelete() {
        assertThatThrownBy {
            AttachmentCliOptionsParser.parse(listOf("orphan", "--delete"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--confirm-bucket")
    }

    @Test
    @DisplayName("orphan 삭제 옵션을 파싱한다")
    fun parseOrphanDeleteOptions() {
        val options =
            AttachmentCliOptionsParser.parse(
                listOf(
                    "orphan",
                    "--delete",
                    "--confirm-bucket=techtaurant-media-dev",
                    "--batch-size=300",
                    "--min-age-hours=48",
                ),
            )

        assertThat(options).isEqualTo(
            AttachmentCliOptions.Orphan(
                batchSize = 300,
                delete = true,
                confirmBucket = "techtaurant-media-dev",
                minAgeHours = 48,
            ),
        )
    }

    @Test
    @DisplayName("S3 제한보다 큰 배치 크기를 거부한다")
    fun rejectBatchSizeOverS3Limit() {
        assertThatThrownBy {
            AttachmentCliOptionsParser.parse(listOf("verify", "--batch-size=1001"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("1,000")
    }

    @Test
    @DisplayName("숫자가 아닌 배치 크기를 거부한다")
    fun rejectNonNumericBatchSize() {
        assertThatThrownBy {
            AttachmentCliOptionsParser.parse(listOf("verify", "--batch-size=many"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("정수")
    }
}
