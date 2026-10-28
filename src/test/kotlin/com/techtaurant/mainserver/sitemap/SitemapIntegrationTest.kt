package com.techtaurant.mainserver.sitemap

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.post.entity.Post
import com.techtaurant.mainserver.post.enums.PostStatusEnum
import com.techtaurant.mainserver.post.infrastructure.out.PostRepository
import com.techtaurant.mainserver.security.SecurityConstants
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.restassured.RestAssured.given
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.TaskScheduler
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Duration
import java.time.Instant
import java.util.UUID

@TestPropertySource(properties = ["sitemap.enabled=true", "sitemap.bucket-name=test", "sitemap.cron=-"])
class SitemapIntegrationTest : IntegrationTest() {
    @Autowired private lateinit var posts: PostRepository

    @Autowired private lateinit var users: UserRepository

    @Autowired private lateinit var sitemapPosts: SitemapPostRepository

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var context: ApplicationContext

    @Autowired private lateinit var tokens: JwtTokenProvider

    @MockitoBean private lateinit var storage: SitemapStorage

    @Test
    fun `only published posts are selected with their persisted modification time`() {
        val user = user(UserRole.USER)
        val published = posts.save(Post("public", "body", user))
        posts.save(Post("private", "body", user, status = PostStatusEnum.PRIVATE))
        posts.save(Post("draft", "body", user, status = PostStatusEnum.DRAFT))
        val deleted = posts.save(Post("deleted", "body", user))
        jdbc.update("DELETE FROM posts WHERE id = ?", deleted.id)
        jdbc.update("UPDATE posts SET updated_at_utc = '2025-01-02T03:04:05Z'::timestamptz WHERE id = ?", published.id)
        assertThat(sitemapPosts.findPublished()).containsExactly(SitemapPost(published.id!!, Instant.parse("2025-01-02T03:04:05Z")))
    }

    @Test
    fun `sitemap and existing tasks have separate schedulers`() {
        val existing = context.getBean("taskScheduler", TaskScheduler::class.java)
        assertThat(context.getBean("sitemapTaskScheduler", TaskScheduler::class.java)).isNotSameAs(existing)
    }

    @Test
    fun `independent providers share a database lease and recover expired leases`() {
        fun provider() =
            JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder().withJdbcTemplate(jdbc).usingDbTime().build())
        val first = provider()
        val second = provider()
        val config = LockConfiguration(Instant.now(), "integration-lock", Duration.ofMinutes(10), Duration.ZERO)
        val held = first.lock(config).orElseThrow()
        assertThat(second.lock(config)).isEmpty()
        held.unlock()
        second.lock(config).orElseThrow()
        jdbc.update("UPDATE shedlock SET lock_until = (now() AT TIME ZONE 'UTC') - interval '1 second' WHERE name = 'integration-lock'")
        val recovered = first.lock(config).orElseThrow()
        recovered.unlock()
    }

    @Test
    fun `manual publication requires an administrator and shares the generation lease`() {
        val path = "${SecurityConstants.ADMIN_API_PREFIX}/sitemap/runs"
        given().post(path).then().statusCode(401)
        val regular = user(UserRole.USER)
        given().cookie(
            JwtConstants.ACCESS_TOKEN_COOKIE,
            tokens.createAccessToken(regular.id!!, regular.role),
        ).post(path).then().statusCode(403)
        val admin = user(UserRole.ADMIN)
        val token = tokens.createAccessToken(admin.id!!, admin.role)
        given().cookie(JwtConstants.ACCESS_TOKEN_COOKIE, token).post(path).then().statusCode(200)
        given().cookie(JwtConstants.ACCESS_TOKEN_COOKIE, token).post(path).then().statusCode(409)
    }

    private fun user(role: UserRole) =
        users.save(
            User(
                name = "sitemap-${role.name}",
                email = "${UUID.randomUUID()}@example.com",
                provider = OAuthProvider.GOOGLE,
                identifier = UUID.randomUUID().toString(),
                role = role,
                profileImageUrl = "https://example.com/image",
            ),
        )
}
