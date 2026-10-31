package com.techtaurant.mainserver.sitemap

import com.techtaurant.mainserver.post.dto.PostForSitemap
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
import java.time.Instant
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

class SitemapEnvironmentTest {
    @ParameterizedTest
    @ValueSource(strings = ["prod", "dev"])
    fun `both environments register sitemap components and publish to the configured bucket`(environment: String) {
        val clientBaseUrl = if (environment == "prod") "https://techtaurant.com" else "https://dev.techtaurant.com"
        val s3 = mockk<S3Client>()
        val posts = mockk<PostRepository>()
        val requests = mutableListOf<PutObjectRequest>()
        val postId = UUID.randomUUID()
        every { posts.findPostsForSitemap(null, 1_000) } returns listOf(PostForSitemap(postId, Instant.EPOCH))
        every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } answers {
            requests.add(firstArg())
            val document =
                secondArg<RequestBody>().contentStreamProvider().newStream().use {
                    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
                }
            val locations = document.getElementsByTagName("loc")
            when (firstArg<PutObjectRequest>().key()) {
                "sitemap/posts-1.xml" -> assertThat(locations.item(0).textContent).isEqualTo("$clientBaseUrl/posts/$postId")
                "sitemap/home.xml" -> assertThat(locations.item(0).textContent).isEqualTo("$clientBaseUrl/posts")
                "sitemap/index.xml" ->
                    assertThat((0 until locations.length).map { locations.item(it).textContent })
                        .containsExactly("$clientBaseUrl/sitemap/home.xml", "$clientBaseUrl/sitemap/posts-1.xml")
            }
            PutObjectResponse.builder().build()
        }

        ApplicationContextRunner()
            .withPropertyValues(
                "app.environment=$environment",
                "app.client-base-url=$clientBaseUrl",
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
