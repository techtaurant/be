package com.techtaurant.mainserver.sitemap

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.config.ShedLockConfig
import com.techtaurant.mainserver.post.dto.PostForSitemap
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

class SitemapIntegrationTest : IntegrationTest() {
    @Autowired private lateinit var posts: PostRepository

    @Autowired private lateinit var users: UserRepository

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var dataSource: DataSource

    @Test
    fun `cursor pages contain only published posts without overlap and use persisted modification time`() {
        val user =
            users.save(
                User(
                    name = "sitemap",
                    email = "${UUID.randomUUID()}@example.com",
                    provider = OAuthProvider.GOOGLE,
                    identifier = UUID.randomUUID().toString(),
                    role = UserRole.USER,
                    profileImageUrl = "https://example.com/image",
                ),
            )
        val published = List(3) { posts.save(Post("public-$it", "body", user)) }
        posts.save(Post("private", "body", user, status = PostStatusEnum.PRIVATE))
        posts.save(Post("draft", "body", user, status = PostStatusEnum.DRAFT))
        val deleted = posts.save(Post("deleted", "body", user))
        jdbc.update("DELETE FROM posts WHERE id = ?", deleted.id)
        val modifiedAt = Instant.parse("2025-01-02T03:04:05Z")
        published.forEach {
            jdbc.update("UPDATE posts SET updated_at_utc = ?::timestamptz WHERE id = ?", modifiedAt.toString(), it.id)
        }
        val first = posts.findPostsForSitemap(null, 2)
        val second = posts.findPostsForSitemap(first.last().id, 2)
        assertThat(first).hasSize(2)
        assertThat(second).hasSize(1)
        assertThat(first + second).containsExactlyElementsOf(published.sortedBy { it.id }.map { PostForSitemap(it.id!!, modifiedAt) })
        assertThat(posts.findPostsForSitemap(second.last().id, 2)).isEmpty()
    }

    @Test
    fun `independent Spring contexts skip the same active lock and honor the minimum lease`() {
        val repository = mockk<PostRepository>()
        val storage = mockk<SitemapStorage>(relaxed = true)
        every { repository.findPostsForSitemap(null, 1_000) } returns emptyList()
        runner(repository, storage).run { first ->
            runner(repository, storage).run { second ->
                val firstScheduler = first.getBean(SitemapScheduler::class.java)
                val secondScheduler = second.getBean(SitemapScheduler::class.java)
                assertThat(AopUtils.isAopProxy(firstScheduler)).isTrue()
                every { storage.publishFile(any(), any()) } answers {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                    val contender = Executors.newSingleThreadExecutor()
                    try {
                        contender.submit { secondScheduler.generate() }.get(5, TimeUnit.SECONDS)
                    } finally {
                        contender.shutdownNow()
                    }
                }
                firstScheduler.generate()
                secondScheduler.generate()
                verify(exactly = 1) { repository.findPostsForSitemap(any(), any()) }
                verify(exactly = 1) { storage.publishFile(any(), any()) }

                jdbc.update("UPDATE shedlock SET lock_until = (now() AT TIME ZONE 'UTC') - interval '1 second'")
                secondScheduler.generate()
                verify(exactly = 2) { repository.findPostsForSitemap(any(), any()) }
            }
        }
    }

    @Test
    fun `failed annotation protected execution can run again after the minimum lease expires`() {
        val repository = mockk<PostRepository>()
        val storage = mockk<SitemapStorage>(relaxed = true)
        every { repository.findPostsForSitemap(null, 1_000) } throws IllegalStateException("DB unavailable")
        runner(repository, storage).run { context ->
            val scheduler = context.getBean(SitemapScheduler::class.java)
            assertThatThrownBy { scheduler.generate() }.isInstanceOf(IllegalStateException::class.java)
            every { repository.findPostsForSitemap(null, 1_000) } returns emptyList()
            scheduler.generate()
            verify(exactly = 0) { storage.publishFile(any(), any()) }
            jdbc.update("UPDATE shedlock SET lock_until = (now() AT TIME ZONE 'UTC') - interval '1 second'")
            scheduler.generate()
            verify(exactly = 1) { storage.publishFile(any(), any()) }
        }
    }

    private fun runner(
        repository: PostRepository,
        storage: SitemapStorage,
    ) = ApplicationContextRunner()
        .withPropertyValues("app.client-base-url=https://techtaurant.com")
        .withUserConfiguration(ShedLockConfig::class.java, SitemapScheduler::class.java, SitemapXmlWriter::class.java)
        .withBean(DataSource::class.java, { dataSource })
        .withBean(PostRepository::class.java, { repository })
        .withBean(SitemapStorage::class.java, { storage })
}
