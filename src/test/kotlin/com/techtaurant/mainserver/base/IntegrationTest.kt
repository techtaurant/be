package com.techtaurant.mainserver.base

import io.restassured.RestAssured
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.utility.DockerImageName
import java.nio.file.Path
import java.time.Duration

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class IntegrationTest {
    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun setUp() {
        cleanDatabase()
        RestAssured.port = port
        RestAssured.basePath = ""
        RestAssured.baseURI = "http://localhost"
    }

    protected fun getBaseUrl(): String = "http://localhost:$port"

    protected fun configureRestAssured() {
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails()
    }

    private fun cleanDatabase() {
        val tableNames =
            jdbcTemplate.queryForList(
                """
                SELECT tablename
                FROM pg_tables
                WHERE schemaname = 'public'
                  AND tablename <> 'flyway_schema_history'
                """.trimIndent(),
                String::class.java,
            )

        if (tableNames.isEmpty()) {
            return
        }

        val truncateQuery = tableNames.joinToString(prefix = "TRUNCATE TABLE ", separator = ", ", postfix = " RESTART IDENTITY CASCADE")
        jdbcTemplate.execute(truncateQuery)
    }

    companion object {
        /**
         * 게시물 검색 인덱스가 pg_bigm 확장을 요구하므로 공식 이미지 대신 확장을 빌드해 넣은 이미지를 쓴다.
         * docker-compose와 같은 Dockerfile을 쓰되 기존 테스트 PostgreSQL 버전은 유지한다.
         */
        private val postgresImageName =
            ImageFromDockerfile("techtaurant/postgres-bigm-test:15-alpine", false)
                .withDockerfile(Path.of("docker/postgres/Dockerfile"))
                .withBuildArg("POSTGRES_VERSION", "15-alpine")
                .get()

        private val postgresContainer =
            PostgreSQLContainer(DockerImageName.parse(postgresImageName).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("techtaurant_test")
                .withUsername("test_user")
                .withPassword("test_password")
                .withExposedPorts(5432)
                // shared_preload_libraries는 서버 시작 시점에만 읽힌다.
                // PostgreSQLContainer 기본 명령의 fsync=off를 덮어쓰게 되므로 함께 넘긴다.
                // 빠지면 테스트마다 수행하는 전체 테이블 TRUNCATE가 디스크로 fsync하며 스위트가 수십 배 느려진다.
                .withCommand("postgres", "-c", "fsync=off", "-c", "shared_preload_libraries=pg_bigm")
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(120)))

        init {
            postgresContainer.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun dynamicProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgresContainer.jdbcUrl }
            registry.add("spring.datasource.username") { postgresContainer.username }
            registry.add("spring.datasource.password") { postgresContainer.password }
        }
    }
}
