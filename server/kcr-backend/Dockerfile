# syntax=docker/dockerfile:1

FROM eclipse-temurin:17-jdk-noble AS build
WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
RUN chmod +x gradlew

COPY src/ src/
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar

FROM eclipse-temurin:17-jre-noble AS runtime
WORKDIR /app

RUN useradd --system --uid 10001 appuser

COPY --from=build /workspace/build/libs/*SNAPSHOT.jar app.jar

USER appuser
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
