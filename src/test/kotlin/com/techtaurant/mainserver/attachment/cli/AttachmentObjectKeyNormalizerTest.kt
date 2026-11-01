package com.techtaurant.mainserver.attachment.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Attachment S3 object key 정규화")
class AttachmentObjectKeyNormalizerTest {
    private val normalizer = AttachmentObjectKeyNormalizer("techtaurant-media-dev", "ap-northeast-2")

    @Test
    @DisplayName("현재 형식의 object key는 그대로 사용한다")
    fun normalizePlainObjectKey() {
        assertThat(normalizer.normalize("posts/post-id/file.png"))
            .isEqualTo("posts/post-id/file.png")
    }

    @Test
    @DisplayName("레거시 virtual-hosted S3 URL을 object key로 변환한다")
    fun normalizeVirtualHostedUrl() {
        assertThat(
            normalizer.normalize(
                "https://techtaurant-media-dev.s3.ap-northeast-2.amazonaws.com/posts/post-id/%ED%95%9C%EA%B8%80%20file.png",
            ),
        ).isEqualTo("posts/post-id/한글 file.png")
    }

    @Test
    @DisplayName("레거시 s3 URI를 object key로 변환한다")
    fun normalizeS3Uri() {
        assertThat(normalizer.normalize("s3://techtaurant-media-dev/users/user-id/profile.png"))
            .isEqualTo("users/user-id/profile.png")
    }

    @Test
    @DisplayName("현재 버킷이 아닌 URL은 안전하게 거부한다")
    fun rejectDifferentBucketUrl() {
        assertThat(
            normalizer.normalize(
                "https://techtaurant-media.s3.ap-northeast-2.amazonaws.com/posts/post-id/file.png",
            ),
        ).isNull()
    }

    @Test
    @DisplayName("DB 조회 후보에는 일반 key와 레거시 URL 형식을 포함한다")
    fun buildDatabaseRepresentations() {
        val representations = normalizer.databaseRepresentations("posts/post-id/한글 file.png")

        assertThat(representations).contains(
            "posts/post-id/한글 file.png",
            "s3://techtaurant-media-dev/posts/post-id/한글 file.png",
            "https://techtaurant-media-dev.s3.ap-northeast-2.amazonaws.com/posts/post-id/%ED%95%9C%EA%B8%80%20file.png",
            "http://s3.amazonaws.com/techtaurant-media-dev/posts/post-id/%ED%95%9C%EA%B8%80%20file.png",
        )
    }
}
