# Dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY ssds-api/build.gradle ssds-api/
COPY ssds-core/build.gradle ssds-core/
COPY ssds-ai/build.gradle ssds-ai/
COPY ssds-ingest/build.gradle ssds-ingest/
COPY ssds-calibration/build.gradle ssds-calibration/
COPY ssds-infra/build.gradle ssds-infra/
RUN chmod +x gradlew \
 && ./gradlew --no-daemon :ssds-api:dependencies > /dev/null
COPY . .
RUN ./gradlew --no-daemon :ssds-api:bootJar -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app \
 && mkdir -p /app/uploads && chown -R app:app /app
COPY --from=build /src/ssds-api/build/libs/ssds.jar app.jar
USER app
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -Xss512k -XX:TieredStopAtLevel=1"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]