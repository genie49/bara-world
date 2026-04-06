FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY build-logic/ build-logic/
COPY libs/ libs/
COPY apps/auth/ apps/auth/
RUN ./gradlew :apps:auth:bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/apps/auth/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
