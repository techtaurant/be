package com.techtaurant.mainserver.attachment.cli

import java.io.PrintStream
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

private const val TMP_OBJECT_KEY_PREFIX = "tmp/"

internal data class AttachmentVerifyResult(
    val checkedCount: Long,
    val missingCount: Long,
    val invalidReferenceCount: Long,
) {
    val hasFindings: Boolean = missingCount > 0 || invalidReferenceCount > 0
}

internal data class AttachmentOrphanResult(
    val scannedCount: Long,
    val orphanCount: Long,
    val deletedCount: Long,
    val protectedBeforeDeleteCount: Long,
    val skippedTmpCount: Long,
    val skippedRecentCount: Long,
) {
    val hasRemainingFindings: Boolean = orphanCount > deletedCount + protectedBeforeDeleteCount
}

internal class AttachmentAuditService(
    private val repository: AttachmentAuditRepository,
    private val objectStore: AttachmentObjectStore,
    private val objectKeyNormalizer: AttachmentObjectKeyNormalizer,
    private val output: PrintStream,
) {
    fun verify(batchSize: Int): AttachmentVerifyResult {
        var afterId: java.util.UUID? = null
        var checkedCount = 0L
        var missingCount = 0L
        var invalidReferenceCount = 0L

        while (true) {
            val references = repository.findConfirmedBatch(afterId, batchSize)
            if (references.isEmpty()) break
            references.forEach { reference ->
                val objectKey = objectKeyNormalizer.normalize(reference.rawObjectKey)
                if (objectKey == null) {
                    invalidReferenceCount++
                    output.println("[INVALID] attachmentId=${reference.attachmentId} objectKey=${reference.rawObjectKey}")
                } else {
                    checkedCount++
                    if (!objectStore.exists(objectKey)) {
                        missingCount++
                        output.println("[MISSING] attachmentId=${reference.attachmentId} objectKey=$objectKey")
                    }
                }
            }
            afterId = references.last().attachmentId
            output.println("[PROGRESS] confirmed attachment ${checkedCount + invalidReferenceCount}건 점검")
        }

        return AttachmentVerifyResult(checkedCount, missingCount, invalidReferenceCount)
    }

    fun findOrphans(
        options: AttachmentCliOptions.Orphan,
        now: Instant = Instant.now(),
    ): AttachmentOrphanResult {
        val deletionManifest = if (options.delete) Files.createTempFile("attachment-orphans-", ".manifest") else null
        var scannedCount = 0L
        var orphanCount = 0L
        var deletedCount = 0L
        var protectedBeforeDeleteCount = 0L
        var skippedTmpCount = 0L
        var skippedRecentCount = 0L
        val cutoff = now.minus(options.minAgeHours, ChronoUnit.HOURS)

        try {
            if (options.delete) {
                requireNoInvalidReferences(options.batchSize)
            }
            deletionManifest?.let(Files::newBufferedWriter).use { manifestWriter ->
                var continuationToken: String? = null
                do {
                    val page = objectStore.listObjects(continuationToken, options.batchSize)
                    scannedCount += page.objects.size
                    val candidates =
                        page.objects.filter { storedObject ->
                            when {
                                storedObject.objectKey.startsWith(TMP_OBJECT_KEY_PREFIX) -> {
                                    skippedTmpCount++
                                    false
                                }

                                storedObject.lastModified.isAfter(cutoff) -> {
                                    skippedRecentCount++
                                    false
                                }

                                else -> true
                            }
                        }
                    val referencedKeys = repository.findReferencedObjectKeys(candidates.map(AttachmentStoredObject::objectKey))
                    candidates.filterNot { it.objectKey in referencedKeys }.forEach { orphan ->
                        orphanCount++
                        output.println("[ORPHAN] objectKey=${orphan.objectKey} lastModified=${orphan.lastModified}")
                        manifestWriter?.run {
                            write(encodeManifestKey(orphan.objectKey))
                            newLine()
                        }
                    }
                    continuationToken = page.nextContinuationToken
                    output.println("[PROGRESS] S3 object ${scannedCount}건 점검")
                } while (continuationToken != null)
            }

            if (deletionManifest != null) {
                Files.newBufferedReader(deletionManifest).use { reader ->
                    val deleteBatch = mutableListOf<String>()
                    reader.lineSequence().forEach { encodedKey ->
                        deleteBatch += decodeManifestKey(encodedKey)
                        if (deleteBatch.size == options.batchSize) {
                            val deleteResult = deleteAfterReferenceRecheck(deleteBatch)
                            deletedCount += deleteResult.deletedCount
                            protectedBeforeDeleteCount += deleteResult.protectedCount
                            deleteBatch.clear()
                        }
                    }
                    val deleteResult = deleteAfterReferenceRecheck(deleteBatch)
                    deletedCount += deleteResult.deletedCount
                    protectedBeforeDeleteCount += deleteResult.protectedCount
                }
            }
        } finally {
            deletionManifest?.let(Files::deleteIfExists)
        }

        return AttachmentOrphanResult(
            scannedCount = scannedCount,
            orphanCount = orphanCount,
            deletedCount = deletedCount,
            protectedBeforeDeleteCount = protectedBeforeDeleteCount,
            skippedTmpCount = skippedTmpCount,
            skippedRecentCount = skippedRecentCount,
        )
    }

    private fun requireNoInvalidReferences(batchSize: Int) {
        var afterId: java.util.UUID? = null
        var invalidReferenceCount = 0L
        while (true) {
            val references = repository.findConfirmedBatch(afterId, batchSize)
            if (references.isEmpty()) break
            references.forEach { reference ->
                if (objectKeyNormalizer.normalize(reference.rawObjectKey) == null) {
                    invalidReferenceCount++
                    output.println("[INVALID] attachmentId=${reference.attachmentId} objectKey=${reference.rawObjectKey}")
                }
            }
            afterId = references.last().attachmentId
        }
        check(invalidReferenceCount == 0L) {
            "해석할 수 없는 CONFIRMED attachment 참조가 ${invalidReferenceCount}건 있어 삭제를 중단합니다."
        }
    }

    private fun deleteAfterReferenceRecheck(candidateKeys: List<String>): AttachmentDeleteResult {
        if (candidateKeys.isEmpty()) return AttachmentDeleteResult(0, 0)
        val referencedKeys = repository.findReferencedObjectKeys(candidateKeys)
        val deletableKeys = candidateKeys.filterNot(referencedKeys::contains)
        objectStore.deleteObjects(deletableKeys)
        if (deletableKeys.isNotEmpty()) {
            output.println("[DELETED] ${deletableKeys.size}건")
        }
        return AttachmentDeleteResult(
            deletedCount = deletableKeys.size.toLong(),
            protectedCount = referencedKeys.size.toLong(),
        )
    }

    private fun encodeManifestKey(objectKey: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(objectKey.toByteArray(Charsets.UTF_8))

    private fun decodeManifestKey(encodedKey: String): String = Base64.getUrlDecoder().decode(encodedKey).toString(Charsets.UTF_8)

    private data class AttachmentDeleteResult(
        val deletedCount: Long,
        val protectedCount: Long,
    )
}
