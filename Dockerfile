FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace

# Resolve Gradle and application dependencies before copying the source so
# ordinary code changes reuse this layer.
COPY gradlew gradle.properties settings.gradle build.gradle bootstrap.gradle ./
COPY gradle/wrapper/ ./gradle/wrapper/
RUN sed -i 's/\r$//' ./gradlew \
    && sh ./gradlew --no-daemon dependencies --configuration runtimeClasspath > /dev/null

COPY src/main/ ./src/main/
RUN sh ./gradlew --no-daemon bootJar \
    && set -- build/libs/*.jar \
    && test "$#" -eq 1 \
    && test -f "$1" \
    && cp "$1" app.jar

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar

ENV SPRING_PROFILES_ACTIVE=staging \
    TZ=UTC \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70.0 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC"
EXPOSE 8080
USER 10001:10001

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
