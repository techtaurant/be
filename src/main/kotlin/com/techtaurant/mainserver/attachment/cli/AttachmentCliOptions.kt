package com.techtaurant.mainserver.attachment.cli

private const val DEFAULT_BATCH_SIZE = 500
private const val MAX_BATCH_SIZE = 1_000
private const val DEFAULT_MIN_AGE_HOURS = 24L

internal sealed interface AttachmentCliOptions {
    data object Help : AttachmentCliOptions

    data class Verify(
        val batchSize: Int,
    ) : AttachmentCliOptions

    data class Orphan(
        val batchSize: Int,
        val delete: Boolean,
        val confirmBucket: String?,
        val minAgeHours: Long,
    ) : AttachmentCliOptions
}

internal object AttachmentCliOptionsParser {
    fun parse(args: List<String>): AttachmentCliOptions {
        if (args.isEmpty() || args.first() in setOf("help", "--help", "-h")) {
            return AttachmentCliOptions.Help
        }

        val command = args.first()
        val flags = parseFlags(args.drop(1))
        val batchSize =
            flags.value("batch-size")?.let { value ->
                requireNotNull(value.toIntOrNull()) { "--batch-size는 정수여야 합니다." }
            } ?: DEFAULT_BATCH_SIZE
        require(batchSize in 1..MAX_BATCH_SIZE) {
            "--batch-size는 1 이상 1,000 이하여야 합니다."
        }

        return when (command) {
            "verify" -> {
                flags.requireOnly("batch-size")
                AttachmentCliOptions.Verify(batchSize)
            }

            "orphan" -> {
                flags.requireOnly("batch-size", "delete", "confirm-bucket", "min-age-hours")
                val minAgeHours =
                    flags.value("min-age-hours")?.let { value ->
                        requireNotNull(value.toLongOrNull()) { "--min-age-hours는 정수여야 합니다." }
                    } ?: DEFAULT_MIN_AGE_HOURS
                require(minAgeHours >= 0) { "--min-age-hours는 0 이상이어야 합니다." }
                val delete = flags.boolean("delete")
                val confirmBucket = flags.value("confirm-bucket")
                require(!delete || !confirmBucket.isNullOrBlank()) {
                    "삭제하려면 --delete와 --confirm-bucket=<현재 버킷명>을 함께 입력해야 합니다."
                }
                AttachmentCliOptions.Orphan(batchSize, delete, confirmBucket, minAgeHours)
            }

            else -> throw IllegalArgumentException("알 수 없는 attachment 명령입니다: $command")
        }
    }

    private fun parseFlags(args: List<String>): ParsedFlags {
        val values = linkedMapOf<String, String?>()
        args.forEach { argument ->
            require(argument.startsWith("--")) { "알 수 없는 인자입니다: $argument" }
            val (name, value) =
                argument.removePrefix("--").split("=", limit = 2).let { parts ->
                    parts.first() to parts.getOrNull(1)
                }
            require(name.isNotBlank()) { "빈 옵션 이름은 사용할 수 없습니다." }
            require(name !in values) { "옵션을 중복해서 사용할 수 없습니다: --$name" }
            values[name] = value
        }
        return ParsedFlags(values)
    }

    private data class ParsedFlags(
        val values: Map<String, String?>,
    ) {
        fun value(name: String): String? {
            if (name !in values) return null
            return requireNotNull(values[name]) { "--${name}은 --$name=<값> 형식으로 입력해야 합니다." }
        }

        fun boolean(name: String): Boolean {
            if (name !in values) return false
            require(values[name] == null) { "--${name}에는 값을 지정하지 않습니다." }
            return true
        }

        fun requireOnly(vararg allowedNames: String) {
            val unknownNames = values.keys - allowedNames.toSet()
            require(unknownNames.isEmpty()) {
                "지원하지 않는 옵션입니다: ${unknownNames.joinToString { "--$it" }}"
            }
        }
    }
}
