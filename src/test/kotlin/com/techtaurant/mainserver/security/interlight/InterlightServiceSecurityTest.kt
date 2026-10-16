package com.techtaurant.mainserver.security.interlight

import com.techtaurant.mainserver.base.IntegrationTest
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.enums.UserRole
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.core.Authentication
import org.springframework.test.context.TestPropertySource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Import(InterlightServiceSecurityTest.ProbeConfig::class)
@TestPropertySource(properties = ["interlight.service-auth.enabled=true", "interlight.service-auth.secret=integration-service-secret"])
class InterlightServiceSecurityTest : IntegrationTest() {
    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @Test
    @DisplayName("서비스 키는 사용자와 다른 principal 및 빈 credentials로 내부 POST를 허용한다")
    fun serviceKeyAuthenticatesOnlyService() {
        given().header("Authorization", "Bearer integration-service-secret")
            .post("/internal/interlight/test/probe").then().statusCode(200)
            .body("principal", equalTo("InterlightServicePrincipal"))
            .body("credentialsAbsent", equalTo(true))
            .body("authorities", equalTo(listOf("INTERLIGHT_SERVICE")))
    }

    @Test
    @DisplayName("키 누락과 잘못된 키는 기존 인증 오류 응답으로 거절한다")
    fun missingAndWrongKeysAreUnauthorized() {
        given().get("/internal/interlight/test/probe").then().statusCode(401).body("status", equalTo(3008))
        given().header("Authorization", "Bearer wrong-key").get("/internal/interlight/test/probe")
            .then().statusCode(401).body("status", equalTo(3008))
    }

    @Test
    @DisplayName("유효 관리자 로그인 쿠키도 서비스 키를 대체하지 못한다")
    fun loginCookieCannotAuthenticateService() {
        val token = jwtTokenProvider.createAccessToken(UUID.randomUUID(), UserRole.ADMIN)
        given().cookie("accessToken", token).get("/internal/interlight/test/probe")
            .then().statusCode(401)
        given().header("Authorization", "Bearer $token").get("/internal/interlight/test/probe")
            .then().statusCode(401)
    }

    @Test
    @DisplayName("로그인 쿠키가 같이 와도 서비스 principal을 사용자 principal로 덮어쓰지 않는다")
    fun loginCookieCannotOverwriteServicePrincipal() {
        val token = jwtTokenProvider.createAccessToken(UUID.randomUUID(), UserRole.USER)
        given().cookie("accessToken", token).header("Authorization", "Bearer integration-service-secret")
            .get("/internal/interlight/test/probe").then().statusCode(200)
            .body("principal", equalTo("InterlightServicePrincipal"))
            .body("credentialsAbsent", equalTo(true))
    }

    @Test
    @DisplayName("중복 Authorization 헤더는 유효 키가 포함되어 있어도 거절한다")
    fun duplicateAuthorizationIsRejected() {
        given().header("Authorization", "Bearer integration-service-secret", "Bearer wrong")
            .get("/internal/interlight/test/probe").then().statusCode(401)
    }

    @Test
    @DisplayName("서비스 키로 사용자 및 관리자 API 권한을 얻을 수 없고 공개 및 health 경로는 유지한다")
    fun otherSecurityChainsAreUnchanged() {
        given().header("Authorization", "Bearer integration-service-secret").get("/api/users/me")
            .then().statusCode(401)
        given().header("Authorization", "Bearer integration-service-secret").get("/admin/test/probe")
            .then().statusCode(401)
        given().get("/open-api/test/interlight-probe").then().statusCode(200)
        given().get("/actuator/health").then().statusCode(200)
        given().get("/actuator/prometheus").then().statusCode(401)
    }

    @TestConfiguration
    class ProbeConfig {
        @Bean
        fun interlightSecurityProbe(): ProbeController = ProbeController()
    }

    @RestController
    class ProbeController {
        @RequestMapping(
            "/internal/interlight/test/probe",
            method = [RequestMethod.GET, RequestMethod.POST],
        )
        fun internalProbe(authentication: Authentication): Map<String, Any> =
            mapOf(
                "principal" to authentication.principal.javaClass.simpleName,
                "credentialsAbsent" to (authentication.credentials == null),
                "authorities" to authentication.authorities.map { it.authority },
            )

        @GetMapping("/open-api/test/interlight-probe")
        fun publicProbe(): String = "public"
    }
}

@Import(InterlightServiceSecurityTest.ProbeConfig::class)
@TestPropertySource(properties = ["interlight.service-auth.secret=integration-service-secret"])
class DisabledInterlightServiceSecurityTest : IntegrationTest() {
    @Test
    @DisplayName("설정 비활성화 상태는 올바른 서비스 키도 거절한다")
    fun disabledAuthenticationFailsClosed() {
        given().header("Authorization", "Bearer integration-service-secret")
            .get("/internal/interlight/test/probe").then().statusCode(401)
    }
}

@Import(InterlightServiceSecurityTest.ProbeConfig::class)
@TestPropertySource(properties = ["interlight.service-auth.enabled=true", "interlight.service-auth.secret="])
class MissingSecretInterlightServiceSecurityTest : IntegrationTest() {
    @Test
    @DisplayName("설정 키가 없으면 Bearer 빈 값이나 임의 키로 인증할 수 없다")
    fun missingSecretFailsClosed() {
        given().header("Authorization", "Bearer ").get("/internal/interlight/test/probe").then().statusCode(401)
        given().header("Authorization", "Bearer integration-service-secret")
            .get("/internal/interlight/test/probe").then().statusCode(401)
    }
}
