package com.techtaurant.mainserver.attachment.dto

import com.techtaurant.mainserver.attachment.enums.AttachmentReferenceType
import jakarta.validation.Validation
import jakarta.validation.Validator
import jakarta.validation.ValidatorFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PresignedUrlRequest 파일 크기 제약")
class PresignedUrlRequestTest {
    private fun fileSizeViolationMessages(fileSize: Long): List<String> {
        val request =
            PresignedUrlRequest(
                fileName = "photo.jpg",
                contentType = "image/jpeg",
                fileSize = fileSize,
                referenceType = AttachmentReferenceType.POST,
            )

        return validator.validate(request)
            .filter { it.propertyPath.toString() == "fileSize" }
            .map { it.message }
    }

    @Test
    @DisplayName("허용 최대 크기와 정확히 같으면 위반이 없다")
    fun fileSize_exactlyMaxAllowed_hasNoViolation() {
        // given & when
        val violations = fileSizeViolationMessages(PresignedUrlRequest.MAX_FILE_SIZE_BYTES)

        // then
        assertThat(violations).isEmpty()
    }

    @Test
    @DisplayName("허용 최대 크기를 1바이트 넘기면 위반이 발생한다")
    fun fileSize_oneByteOverMaxAllowed_hasViolation() {
        // given & when
        val violations = fileSizeViolationMessages(PresignedUrlRequest.MAX_FILE_SIZE_BYTES + 1)

        // then
        assertThat(violations).isNotEmpty()
    }

    @Test
    @DisplayName("허용 최대 크기는 30MB로 고정한다")
    fun maxFileSizeBytes_isThirtyMegabytes() {
        // given & when & then
        assertThat(PresignedUrlRequest.MAX_FILE_SIZE_BYTES).isEqualTo(31_457_280L)
    }

    companion object {
        private lateinit var validatorFactory: ValidatorFactory
        private lateinit var validator: Validator

        @BeforeAll
        @JvmStatic
        fun setUpValidator() {
            validatorFactory = Validation.buildDefaultValidatorFactory()
            validator = validatorFactory.validator
        }

        @AfterAll
        @JvmStatic
        fun tearDownValidator() {
            validatorFactory.close()
        }
    }
}
