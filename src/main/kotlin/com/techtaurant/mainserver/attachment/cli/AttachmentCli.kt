package com.techtaurant.mainserver.attachment.cli

import io.github.cdimascio.dotenv.Dotenv
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.io.PrintStream
import java.sql.DriverManager
import kotlin.system.exitProcess

private const val EXIT_SUCCESS = 0
private const val EXIT_ERROR = 1
private const val EXIT_FINDINGS = 2
private const val EXIT_USAGE = 64

object AttachmentCli {
    fun run(
        args: List<String>,
        environment: Map<String, String> = AttachmentCliEnvironment.load(),
        output: PrintStream = System.out,
        error: PrintStream = System.err,
    ): Int {
        val options =
            try {
                AttachmentCliOptionsParser.parse(args)
            } catch (exception: IllegalArgumentException) {
                error.println("[ERROR] ${exception.message}")
                error.println(usage())
                return EXIT_USAGE
            }
        if (options == AttachmentCliOptions.Help) {
            output.println(usage())
            return EXIT_SUCCESS
        }

        return try {
            val config = AttachmentCliRuntimeConfig.from(environment)
            if (options is AttachmentCliOptions.Orphan && options.delete) {
                require(options.confirmBucket == config.bucketName) {
                    "--confirm-bucket 값이 AWS_S3_BUCKET_NAME과 일치하지 않습니다."
                }
            }
            output.println("[START] bucket=${config.bucketName} region=${config.region}")
            runCommand(options, config, output)
        } catch (exception: Exception) {
            error.println("[ERROR] ${exception.message ?: exception.javaClass.simpleName}")
            EXIT_ERROR
        }
    }

    private fun runCommand(
        options: AttachmentCliOptions,
        config: AttachmentCliRuntimeConfig,
        output: PrintStream,
    ): Int =
        DriverManager.getConnection(config.jdbcUrl, config.dbUsername, config.dbPassword).use { connection ->
            connection.isReadOnly = true
            S3Client.builder().region(Region.of(config.region)).build().use { s3Client ->
                val normalizer = AttachmentObjectKeyNormalizer(config.bucketName, config.region)
                val repository = JooqAttachmentAuditRepository(DSL.using(connection, SQLDialect.POSTGRES), normalizer)
                val objectStore = S3AttachmentObjectStore(s3Client, config.bucketName)
                val service = AttachmentAuditService(repository, objectStore, normalizer, output)
                when (options) {
                    AttachmentCliOptions.Help -> EXIT_SUCCESS
                    is AttachmentCliOptions.Verify -> runVerify(service, options, output)
                    is AttachmentCliOptions.Orphan -> runOrphan(service, options, output)
                }
            }
        }

    private fun runVerify(
        service: AttachmentAuditService,
        options: AttachmentCliOptions.Verify,
        output: PrintStream,
    ): Int {
        val result = service.verify(options.batchSize)
        output.println(
            "[RESULT] checked=${result.checkedCount} missing=${result.missingCount} " +
                "invalid=${result.invalidReferenceCount}",
        )
        return if (result.hasFindings) EXIT_FINDINGS else EXIT_SUCCESS
    }

    private fun runOrphan(
        service: AttachmentAuditService,
        options: AttachmentCliOptions.Orphan,
        output: PrintStream,
    ): Int {
        val result = service.findOrphans(options)
        output.println(
            "[RESULT] scanned=${result.scannedCount} orphan=${result.orphanCount} deleted=${result.deletedCount} " +
                "protectedBeforeDelete=${result.protectedBeforeDeleteCount} " +
                "skippedTmp=${result.skippedTmpCount} skippedRecent=${result.skippedRecentCount}",
        )
        return if (result.hasRemainingFindings) EXIT_FINDINGS else EXIT_SUCCESS
    }

    private fun usage(): String =
        """
        사용법:
          attachment verify [--batch-size=500]
          attachment orphan [--batch-size=500] [--min-age-hours=24]
          attachment orphan --delete --confirm-bucket=<버킷명> [--batch-size=500] [--min-age-hours=24]

        verify는 CONFIRMED attachment의 DB object_key가 현재 S3 버킷에 존재하는지 확인합니다.
        orphan은 DB에서 참조하지 않는 S3 object를 찾습니다. tmp/와 유예 시간 이내 object는 제외합니다.
        """.trimIndent()
}

internal object AttachmentCliEnvironment {
    fun load(systemEnvironment: Map<String, String> = System.getenv()): Map<String, String> {
        val dotenvEnvironment =
            Dotenv.configure()
                .ignoreIfMissing()
                .load()
                .entries()
                .associate { entry -> entry.key to entry.value }
        return dotenvEnvironment + systemEnvironment
    }
}

internal data class AttachmentCliRuntimeConfig(
    val jdbcUrl: String,
    val dbUsername: String,
    val dbPassword: String,
    val region: String,
    val bucketName: String,
) {
    companion object {
        fun from(environment: Map<String, String>): AttachmentCliRuntimeConfig {
            fun required(name: String): String =
                environment[name]?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("필수 환경변수가 없습니다: $name")

            val dbHost = required("DB_HOST")
            val dbPort = environment["DB_PORT"]?.takeIf(String::isNotBlank) ?: "5432"
            val dbName = required("DB_NAME")
            return AttachmentCliRuntimeConfig(
                jdbcUrl = "jdbc:postgresql://$dbHost:$dbPort/$dbName",
                dbUsername = required("DB_USERNAME"),
                dbPassword = required("DB_PASSWORD"),
                region = environment["AWS_REGION"]?.takeIf(String::isNotBlank) ?: "ap-northeast-2",
                bucketName = required("AWS_S3_BUCKET_NAME"),
            )
        }
    }
}

fun main(args: Array<String>) {
    exitProcess(AttachmentCli.run(args.toList()))
}
