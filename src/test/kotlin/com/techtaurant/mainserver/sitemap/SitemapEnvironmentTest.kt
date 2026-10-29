package com.techtaurant.mainserver.sitemap

import com.techtaurant.mainserver.common.lock.ShedLock
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception

class SitemapEnvironmentTest {
    @ParameterizedTest
    @ValueSource(strings = ["prod", "dev"])
    fun `both environments register sitemap components and publish to the configured bucket`(environment: String) {
        val s3 = mockk<S3Client>()
        val posts = mockk<SitemapPostRepository>()
        val lock = mockk<ShedLock>()
        val requests = mutableListOf<PutObjectRequest>()
        every { s3.headObject(any<HeadObjectRequest>()) } throws S3Exception.builder().statusCode(404).build()
        every { s3.putObject(capture(requests), any<RequestBody>()) } returns PutObjectResponse.builder().build()
        every { posts.findPublished() } returns emptyList()
        every { lock.withLock<SitemapGenerationResult>(any(), any(), any(), any()) } answers {
            lastArg<() -> SitemapGenerationResult>().invoke()
        }

        ApplicationContextRunner()
            .withPropertyValues(
                "app.environment=$environment",
                "aws.s3.public-bucket-name=techtaurant-public-$environment",
            )
            .withBean(S3Client::class.java, { s3 })
            .withBean(SitemapPostRepository::class.java, { posts })
            .withBean(ShedLock::class.java, { lock })
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withUserConfiguration(
                SitemapConfig::class.java,
                SitemapScheduler::class.java,
                SitemapStorage::class.java,
                SitemapXmlWriter::class.java,
                SitemapGenerationService::class.java,
                AdminSitemapController::class.java,
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(AdminSitemapController::class.java)
                context.getBean(SitemapScheduler::class.java).generate()
                assertThat(requests.map { it.bucket() }).containsExactly(
                    "techtaurant-public-$environment",
                    "techtaurant-public-$environment",
                )
                assertThat(requests.map { it.key() }).containsExactly("sitemap/posts.xml", "sitemap/index.xml")
            }
    }
}
