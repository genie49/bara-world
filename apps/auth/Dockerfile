FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# 1) Gradle wrapper + 빌드 스크립트 (변경 드묾 → 레이어 캐시)
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY build-logic/ build-logic/
COPY libs/common/build.gradle.kts libs/common/
COPY apps/auth/build.gradle.kts apps/auth/

# 2) 의존성 다운로드 (Gradle 캐시를 BuildKit 마운트로 유지)
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :apps:auth:dependencies --no-daemon -q

# 3) 소스 복사 + 빌드
COPY libs/ libs/
COPY apps/auth/ apps/auth/
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :apps:auth:bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/apps/auth/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
