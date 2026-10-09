# Multi-stage build
# Uses eclipse-temurin:25 — available as of Java 25 LTS release.

FROM eclipse-temurin:25-jdk AS builder
WORKDIR /workspace
COPY gradlew .
COPY gradle gradle
COPY settings.gradle .
COPY build.gradle .
COPY applications applications
COPY domain domain
COPY infrastructure infrastructure
RUN chmod +x gradlew && ./gradlew :app-service:bootJar --no-daemon -x test

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
COPY --from=builder /workspace/applications/app-service/build/libs/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
