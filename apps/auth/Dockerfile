FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY apps/auth/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
