plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    kotlin("kapt") version "1.9.25"
    id("org.springframework.boot") version "3.5.7"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.jpa") version "1.9.25"
    id("com.diffplug.spotless") version "6.25.0"
    jacoco
}

group = "com.techtaurant"
version = "0.0.1-SNAPSHOT"
description = "main-server"

extra["opentelemetry.version"] = "1.60.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(enforcedPlatform("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:2.26.1"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Database
    runtimeOnly("org.postgresql:postgresql")
    implementation("com.github.gavlyukovskiy:p6spy-spring-boot-starter:1.9.0")

    // Environment Variables
    implementation("me.paulschwarz:spring-dotenv:4.0.0")

    // Testing
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // E2E Testing
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers:1.21.4")
    testImplementation("org.testcontainers:postgresql:1.21.4")
    testImplementation("org.testcontainers:testcontainers-bom:1.21.4")

    // REST Assured for API Testing
    testImplementation("io.rest-assured:rest-assured:5.4.0")

    // Test Database
    testRuntimeOnly("org.postgresql:postgresql")

    // Flyway
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Swagger
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.14")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // UUID V7
    implementation("com.github.f4b6a3:uuid-creator:6.0.0")

    // JPA Metamodel (타입 안전 Criteria Query)
    kapt("org.hibernate.orm:hibernate-jpamodelgen")

    // Caffeine Cache
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // HTML Fetching and Sanitization
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.microsoft.playwright:playwright:1.61.0") {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }

    // Observability
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.opentelemetry:opentelemetry-api")
    implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter")

    // AWS SDK v2
    implementation(platform("software.amazon.awssdk:bom:2.25.0"))
    implementation("software.amazon.awssdk:s3")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

// JaCoCo Configuration
jacoco {
    toolVersion = "0.8.11"
}

// Configure test task
tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy("jacocoTestReport")
}

// Configure bootRun task for development
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    environment("SPRING_PROFILES_ACTIVE", "dev")
}

// Configure JaCoCo Test Report Task
tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.withType<Test>())

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    classDirectories.setFrom(
        files(
            classDirectories.files.map { file ->
                fileTree(file) {
                    exclude(
                        "**/config/**",
                        "**/entity/**",
                        "**/dto/**",
                        "**/Application.class",
                        "**/ApplicationKt.class",
                    )
                }
            },
        ),
    )

    finalizedBy("jacocoTestCoverageVerification")
}

// Configure JaCoCo Coverage Verification Task
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn("jacocoTestReport")

    violationRules {
        rule {
            element = "BUNDLE"

            limit {
                counter = "METHOD"
                value = "COVEREDRATIO"
                minimum = "0.70".toBigDecimal()
            }

            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.60".toBigDecimal()
            }

            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}

// Spotless Configuration
spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.2.1")
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.2.1")
    }
}
