package com.techtaurant.mainserver.attachment.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Attachment CLI 환경변수 로딩")
class AttachmentCliEnvironmentTest {
    @Test
    @DisplayName("시스템 환경변수는 .env 값보다 우선한다")
    fun systemEnvironmentOverridesDotenv() {
        val environment =
            AttachmentCliEnvironment.load(
                systemEnvironment = mapOf("DB_HOST" to "system-host"),
            )

        assertThat(environment["DB_HOST"]).isEqualTo("system-host")
    }
}
