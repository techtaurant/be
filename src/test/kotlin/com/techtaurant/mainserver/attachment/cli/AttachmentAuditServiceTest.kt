package com.techtaurant.mainserver.attachment.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant
import java.util.UUID

@DisplayName("Attachment 정합성 점검 서비스")
class AttachmentAuditServiceTest {
    private val now = Instant.parse("2026-08-23T00:00:00Z")
    private val normalizer = AttachmentObjectKeyNormalizer("techtaurant-media-dev", "ap-northeast-2")

    @Test
    @DisplayName("CONFIRMED attachment를 배치로 읽어 누락과 잘못된 참조를 찾는다")
    fun verifyReferencesInBatches() {
        val references =
            listOf(
                reference("posts/1/existing.png"),
                reference("posts/2/missing.png"),
                reference("https://another-bucket.s3.ap-northeast-2.amazonaws.com/posts/3/wrong.png"),
            ).sortedBy(AttachmentObjectReference::attachmentId)
        val repository = FakeAttachmentAuditRepository(references, setOf("posts/1/existing.png", "posts/2/missing.png"))
        val objectStore = FakeAttachmentObjectStore(existingKeys = setOf("posts/1/existing.png"))
        val service = service(repository, objectStore)

        val result = service.verify(batchSize = 2)

        assertThat(repository.requestedBatchSizes).containsOnly(2)
        assertThat(result.checkedCount).isEqualTo(2)
        assertThat(result.missingCount).isEqualTo(1)
        assertThat(result.invalidReferenceCount).isEqualTo(1)
        assertThat(result.hasFindings).isTrue()
    }

    @Test
    @DisplayName("orphan 점검은 tmp와 최근 object를 제외하고 삭제하지 않는다")
    fun auditOrphansWithoutDeleting() {
        val old = now.minusSeconds(48 * 60 * 60)
        val objectStore =
            FakeAttachmentObjectStore(
                pages =
                    listOf(
                        listOf(
                            AttachmentStoredObject("posts/1/referenced.png", old),
                            AttachmentStoredObject("posts/2/orphan.png", old),
                        ),
                        listOf(
                            AttachmentStoredObject("tmp/uploading.png", old),
                            AttachmentStoredObject("users/1/recent.png", now.minusSeconds(60)),
                        ),
                    ),
            )
        val repository = FakeAttachmentAuditRepository(referencedKeys = setOf("posts/1/referenced.png"))
        val service = service(repository, objectStore)

        val result =
            service.findOrphans(
                AttachmentCliOptions.Orphan(2, delete = false, confirmBucket = null, minAgeHours = 24),
                now,
            )

        assertThat(result.scannedCount).isEqualTo(4)
        assertThat(result.orphanCount).isEqualTo(1)
        assertThat(result.deletedCount).isZero()
        assertThat(result.protectedBeforeDeleteCount).isZero()
        assertThat(result.skippedTmpCount).isEqualTo(1)
        assertThat(result.skippedRecentCount).isEqualTo(1)
        assertThat(objectStore.deletedKeys).isEmpty()
    }

    @Test
    @DisplayName("삭제 모드는 전체 탐색 후 DB 참조를 다시 확인하여 orphan만 배치 삭제한다")
    fun deleteOrphansAfterReferenceRecheck() {
        val old = now.minusSeconds(48 * 60 * 60)
        val orphanKeys = (1..5).map { "posts/$it/orphan.png" }
        val objectStore =
            FakeAttachmentObjectStore(
                pages = listOf(orphanKeys.map { key -> AttachmentStoredObject(key, old) }),
            )
        val repository = FakeAttachmentAuditRepository()
        val service = service(repository, objectStore)

        val result =
            service.findOrphans(
                AttachmentCliOptions.Orphan(
                    batchSize = 2,
                    delete = true,
                    confirmBucket = "techtaurant-media-dev",
                    minAgeHours = 24,
                ),
                now,
            )

        assertThat(result.orphanCount).isEqualTo(5)
        assertThat(result.deletedCount).isEqualTo(5)
        assertThat(result.protectedBeforeDeleteCount).isZero()
        assertThat(objectStore.deleteBatches).allMatch { batch -> batch.size <= 2 }
        assertThat(objectStore.deletedKeys).containsExactlyInAnyOrderElementsOf(orphanKeys)
        assertThat(repository.referenceLookupCount).isGreaterThan(1)
    }

    private fun reference(objectKey: String): AttachmentObjectReference = AttachmentObjectReference(UUID.randomUUID(), objectKey)

    private fun service(
        repository: AttachmentAuditRepository,
        objectStore: AttachmentObjectStore,
    ): AttachmentAuditService =
        AttachmentAuditService(
            repository,
            objectStore,
            normalizer,
            PrintStream(ByteArrayOutputStream()),
        )
}

private class FakeAttachmentAuditRepository(
    private val references: List<AttachmentObjectReference> = emptyList(),
    var referencedKeys: Set<String> = emptySet(),
) : AttachmentAuditRepository {
    val requestedBatchSizes = mutableListOf<Int>()
    var referenceLookupCount = 0

    override fun findConfirmedBatch(
        afterId: UUID?,
        batchSize: Int,
    ): List<AttachmentObjectReference> {
        requestedBatchSizes += batchSize
        return references.filter { afterId == null || it.attachmentId > afterId }.take(batchSize)
    }

    override fun findReferencedObjectKeys(objectKeys: Collection<String>): Set<String> {
        referenceLookupCount++
        return objectKeys.filterTo(mutableSetOf(), referencedKeys::contains)
    }
}

private class FakeAttachmentObjectStore(
    private val existingKeys: Set<String> = emptySet(),
    private val pages: List<List<AttachmentStoredObject>> = emptyList(),
) : AttachmentObjectStore {
    val deleteBatches = mutableListOf<List<String>>()
    val deletedKeys: List<String>
        get() = deleteBatches.flatten()

    override fun exists(objectKey: String): Boolean = objectKey in existingKeys

    override fun listObjects(
        continuationToken: String?,
        maxKeys: Int,
    ): AttachmentStoredObjectPage {
        val pageIndex = continuationToken?.toInt() ?: 0
        val objects = pages.getOrElse(pageIndex) { emptyList() }
        return AttachmentStoredObjectPage(
            objects = objects,
            nextContinuationToken = (pageIndex + 1).takeIf { it < pages.size }?.toString(),
        )
    }

    override fun deleteObjects(objectKeys: List<String>) {
        deleteBatches += objectKeys.toList()
    }
}
