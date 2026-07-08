# syntax=docker/dockerfile:1

# Build stage: 네이티브 플랫폼에서 실행하여 QEMU 에뮬레이션 오버헤드 제거
# Java JAR은 플랫폼 독립적이므로 builder를 arm64로 에뮬레이션할 필요 없음
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk AS builder

WORKDIR /app

# 의존성 캐시 레이어: 빌드 설정 파일만 먼저 복사하여 의존성 다운로드
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .

# Gradle 캐시를 BuildKit cache mount로 유지하여 빌드 간 의존성 재다운로드 방지
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    chmod +x ./gradlew && ./gradlew dependencies --no-daemon

# 소스코드 복사 후 빌드 (의존성 캐시 + Gradle 빌드 캐시 활용)
COPY src src

RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew bootJar --no-daemon

# Runtime stage: Playwright와 버전을 맞춘 browser/system dependency 포함 이미지 사용
FROM mcr.microsoft.com/playwright/java:v1.61.0-noble

WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
