package com.techtaurant.mainserver.sitemap

import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse

class SitemapEnvironmentTest {
    @ParameterizedTest
    @ValueSource(strings = ["prod", "dev"])
    fun `both environments register sitemap components and publish to the configured bucket`(environment: String) {
        val s3 = mockk<S3Client>()
        val posts = mockk<PostRepository>()
        val requests = mutableListOf<PutObjectRequest>()
        every { s3.putObject(capture(requests), any<RequestBody>()) } returns PutObjectResponse.builder().build()
        every { posts.findPostsForSitemap(null, 1_000) } returns emptyList()

        ApplicationContextRunner()
            .withPropertyValues(
                "app.environment=$environment",
                "aws.s3.public-bucket-name=techtaurant-public-$environment",
            )
            .withBean(S3Client::class.java, { s3 })
            .withBean(PostRepository::class.java, { posts })
            .withUserConfiguration(
                SitemapScheduler::class.java,
                SitemapStorage::class.java,
                SitemapXmlWriter::class.java,
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(SitemapScheduler::class.java)
                context.getBean(SitemapScheduler::class.java).generate()
                assertThat(requests.map { it.bucket() }).containsExactly(
                    "techtaurant-public-$environment",
                    "techtaurant-public-$environment",
                    "techtaurant-public-$environment",
                )
                assertThat(requests.map { it.key() }).containsExactly("sitemap/posts-1.xml", "sitemap/home.xml", "sitemap/index.xml")
            }
    }
}
