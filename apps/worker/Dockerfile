FROM eclipse-temurin:25-jdk AS builder

WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY packages/contracts-jvm packages/contracts-jvm
COPY apps/worker apps/worker

RUN ./gradlew --no-daemon :apps:worker:bootJar

FROM eclipse-temurin:25-jre

WORKDIR /app

COPY --from=builder /workspace/apps/worker/build/libs/*.jar /app/app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
