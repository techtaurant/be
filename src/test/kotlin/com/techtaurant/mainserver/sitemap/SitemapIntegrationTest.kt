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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.TaskScheduler
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Instant
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

@TestPropertySource(properties = ["app.environment=prod", "aws.s3.public-bucket-name=test"])
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
    fun `admin publishes only public URLs with persisted timestamps and duplicate runs are rejected`() {
        val path = "${SecurityConstants.ADMIN_API_PREFIX}/sitemap/runs"
        given().post(path).then().statusCode(401)
        val regular = user(UserRole.USER)
        given().cookie(
            JwtConstants.ACCESS_TOKEN_COOKIE,
            tokens.createAccessToken(regular.id!!, regular.role),
        ).post(path).then().statusCode(403)
        val admin = user(UserRole.ADMIN)
        val published = posts.save(Post("public", "body", admin))
        posts.save(Post("private", "body", admin, status = PostStatusEnum.PRIVATE))
        posts.save(Post("draft", "body", admin, status = PostStatusEnum.DRAFT))
        val deleted = posts.save(Post("deleted", "body", admin))
        jdbc.update("DELETE FROM posts WHERE id = ?", deleted.id)
        val updatedAt = Instant.parse("2025-01-02T03:04:05Z")
        jdbc.update("UPDATE posts SET updated_at_utc = ?::timestamptz WHERE id = ?", updatedAt.toString(), published.id)
        val token = tokens.createAccessToken(admin.id!!, admin.role)
        val response = given().cookie(JwtConstants.ACCESS_TOKEN_COOKIE, token).post(path).then().statusCode(200).extract().jsonPath()
        given().cookie(JwtConstants.ACCESS_TOKEN_COOKIE, token).post(path).then().statusCode(409)

        val xml = ArgumentCaptor.forClass(ByteArray::class.java)
        verify(storage).publish(xml.capture() ?: byteArrayOf(), isNull())
        val document =
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(xml.value.inputStream())
        assertThat(document.documentElement.namespaceURI).isEqualTo("http://www.sitemaps.org/schemas/sitemap/0.9")
        assertThat(document.getElementsByTagName("url").length).isEqualTo(1)
        assertThat(document.getElementsByTagName("loc").item(0).textContent)
            .isEqualTo("https://techtaurant.com/posts/${published.id}")
        assertThat(document.getElementsByTagName("lastmod").item(0).textContent).isEqualTo(updatedAt.toString())
        assertThat(response.getInt("data.urlCount")).isEqualTo(1)
        assertThat(response.getInt("data.sizeBytes")).isEqualTo(xml.value.size)
        assertThat(response.getString("data.objectKey")).isEqualTo("seo/sitemap.xml")
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
