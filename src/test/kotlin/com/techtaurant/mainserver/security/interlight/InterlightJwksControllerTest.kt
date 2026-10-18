package com.techtaurant.mainserver.security.interlight

import com.fasterxml.jackson.databind.ObjectMapper
import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.enums.UserRole
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.jsonwebtoken.Jwts
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID

class InterlightJwksControllerTest : IntegrationTest() {
    @Test
    @DisplayName("공개 JWKS는 표준 공개키만 반환하고 실제 OpenAPI 응답 스키마와 일치한다")
    fun publicJwksMatchesGeneratedSchema() {
        val response = given().get("/open-api/interlight/jwks").then().statusCode(200).extract().asString()
        val mapper = ObjectMapper()
        val jwks = mapper.readTree(response)
        assertThat(jwks.fieldNames().asSequence().toList()).containsExactly("keys")
        val key = jwks.path("keys").single()
        assertThat(key.fieldNames().asSequence().toList()).containsExactlyInAnyOrder("kty", "use", "alg", "kid", "n", "e")
        assertThat(key.path("kid").asText()).isEqualTo("http-current")
        assertThat(key.path("alg").asText()).isEqualTo("RS256")
        val openapi = given().get("/v3/api-docs").then().statusCode(200).extract().asString()
        Files.writeString(Path.of("qa-results/roky-harness/issue-210/openapi.json"), openapi)
        val document = mapper.readTree(openapi)
        val operation = document.path("paths").path("/open-api/interlight/jwks").path("get")
        val schema = operation.path("responses").path("200").path("content").elements().next().path("schema")
        assertThat(schema.path("\$ref").asText()).isEqualTo("#/components/schemas/InterlightJwksResponse")
        val schemas = document.path("components").path("schemas")
        assertThat(schemas.path("InterlightJwksResponse").path("properties").fieldNames().asSequence().toList()).containsExactly("keys")
        assertThat(schemas.path("InterlightPublicJwk").path("required").map { it.asText() })
            .containsExactlyInAnyOrder("kty", "use", "alg", "kid", "n", "e")
        assertThat(operation.path("responses").has("503")).isTrue()
    }

    companion object {
        private val keyPair = Jwts.SIG.RS256.keyPair().build()

        @JvmStatic
        @DynamicPropertySource
        fun keys(registry: DynamicPropertyRegistry) {
            registry.add("interlight.jwt.enabled") { true }
            registry.add("interlight.jwt.kid") { "http-current" }
            registry.add("interlight.jwt.private-key-pem") {
                "-----BEGIN PRIVATE KEY-----\n${Base64.getEncoder().encodeToString(keyPair.private.encoded)}\n-----END PRIVATE KEY-----"
            }
        }
    }
}

@TestPropertySource(
    properties = ["interlight.jwt.enabled=true", "interlight.jwt.kid=invalid", "interlight.jwt.private-key-pem=invalid-pem"],
)
class InvalidInterlightJwksControllerTest : IntegrationTest() {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var loginTokens: JwtTokenProvider

    @Test
    @DisplayName("잘못된 RSA 설정은 JWKS만 503으로 거절하고 기존 HMAC 로그인 API를 유지한다")
    fun invalidRsaConfigurationDoesNotBreakLogin() {
        val id = UUID.randomUUID().toString()
        val user =
            userRepository.save(
                User(
                    name = "RSA regression",
                    email = "$id@example.com",
                    provider = OAuthProvider.SYSTEM,
                    identifier = id,
                    role = UserRole.USER,
                    profileImageUrl = "",
                ),
            )
        val userId = requireNotNull(user.id)
        given().get("/open-api/interlight/jwks").then().statusCode(503).body("status", equalTo(3201))
        given().cookie("accessToken", loginTokens.createAccessToken(userId, UserRole.USER))
            .get("/api/users/me").then().statusCode(200).body("data.id", equalTo(userId.toString()))
    }
}
