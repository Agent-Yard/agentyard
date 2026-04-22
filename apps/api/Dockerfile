FROM eclipse-temurin:25-jdk AS builder

WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY packages/contracts-jvm packages/contracts-jvm
COPY packages/persistence-jvm packages/persistence-jvm
COPY packages/shared-redis-jvm packages/shared-redis-jvm
COPY apps/api apps/api
COPY apps/worker apps/worker

RUN ./gradlew --no-daemon :apps:api:bootJar

FROM eclipse-temurin:25-jre

WORKDIR /app

COPY --from=builder /workspace/apps/api/build/libs/*.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
