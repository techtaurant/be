package com.techtaurant.mainserver.attachment.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse
import software.amazon.awssdk.services.s3.model.S3Error
import software.amazon.awssdk.services.s3.presigner.S3Presigner

@DisplayName("S3 오브젝트 배치 삭제 단위 테스트")
class S3StorageServiceTest {
    private val s3Client: S3Client = mockk()
    private val s3Presigner: S3Presigner = mockk()

    private val s3StorageService =
        S3StorageService(
            s3Client = s3Client,
            s3Presigner = s3Presigner,
            bucketName = "test-bucket",
        )

    @Test
    @DisplayName("삭제할 키가 API 상한을 넘으면 요청을 나눠 보낸다")
    fun deleteObjects_moreKeysThanRequestLimit_splitsIntoMultipleRequests() {
        // given - DeleteObjects API 상한(1000)을 한 개 넘긴 키 목록
        val objectKeys = (1..1001).map { "tmp/$it/image.jpg" }
        val sentRequests = mutableListOf<DeleteObjectsRequest>()
        every { s3Client.deleteObjects(capture(sentRequests)) } returns DeleteObjectsResponse.builder().build()

        // when
        s3StorageService.deleteObjects(objectKeys)

        // then - 어느 요청도 상한을 넘지 않고, 키는 하나도 빠지지 않는다
        assertThat(sentRequests.map { it.delete().objects().size }).containsExactly(1000, 1)
        assertThat(sentRequests.flatMap { request -> request.delete().objects().map { it.key() } })
            .containsExactlyElementsOf(objectKeys)
    }

    @Test
    @DisplayName("삭제할 키가 상한 이하면 요청을 한 번만 보낸다")
    fun deleteObjects_keysWithinRequestLimit_sendsSingleRequest() {
        // given
        val objectKeys = (1..1000).map { "tmp/$it/image.jpg" }
        val sentRequests = mutableListOf<DeleteObjectsRequest>()
        every { s3Client.deleteObjects(capture(sentRequests)) } returns DeleteObjectsResponse.builder().build()

        // when
        s3StorageService.deleteObjects(objectKeys)

        // then
        assertThat(sentRequests.map { it.delete().objects().size }).containsExactly(1000)
    }

    @Test
    @DisplayName("삭제할 키가 없으면 S3를 호출하지 않는다")
    fun deleteObjects_emptyKeys_skipsRequest() {
        // when
        s3StorageService.deleteObjects(emptyList())

        // then - 빈 Delete 요청은 S3가 MalformedXML로 거절한다
        verify(exactly = 0) { s3Client.deleteObjects(any<DeleteObjectsRequest>()) }
    }

    @Test
    @DisplayName("이미 지워진 키만 실패로 돌아오면 삭제에 성공한 것으로 본다")
    fun deleteObjects_alreadyDeletedKeyReported_treatsAsSuccess() {
        // given - 정리를 재시도해 이미 없는 키를 다시 지우는 상황
        every { s3Client.deleteObjects(any<DeleteObjectsRequest>()) } returns
            DeleteObjectsResponse.builder()
                .errors(S3Error.builder().key("tmp/gone/image.jpg").code("NoSuchKey").build())
                .build()

        // when
        val undeletedObjectKeys = s3StorageService.deleteObjects(listOf("tmp/gone/image.jpg"))

        // then - 여기서 남은 키로 보고하면 재시도가 영원히 완료되지 못한다
        assertThat(undeletedObjectKeys).isEmpty()
    }

    @Test
    @DisplayName("지우지 못한 키를 돌려줘 다음 정리가 다시 시도하게 한다")
    fun deleteObjects_keyLeftUndeleted_returnsFailedKey() {
        // given - 요청 자체는 성공하지만 키 하나가 권한 문제로 남은 응답
        every { s3Client.deleteObjects(any<DeleteObjectsRequest>()) } returns
            DeleteObjectsResponse.builder()
                .errors(S3Error.builder().key("posts/kept/image.jpg").code("AccessDenied").build())
                .build()

        // when
        val undeletedObjectKeys = s3StorageService.deleteObjects(listOf("posts/kept/image.jpg"))

        // then - 빠뜨리면 남아 있는 객체를 지운 것으로 착각한 채 진행한다
        assertThat(undeletedObjectKeys).containsExactly("posts/kept/image.jpg")
    }
}
