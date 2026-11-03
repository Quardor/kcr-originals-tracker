# syntax=docker/dockerfile:1

# ---------- build ----------
FROM eclipse-temurin:17-jdk-noble AS build
WORKDIR /workspace

# Сначала только скрипты сборки: слой с зависимостями не инвалидируется
# при правках исходников.
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
RUN chmod +x gradlew

COPY src/ src/
# bootJar сам по себе тесты не запускает; кэш Gradle переживает пересборки
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar

# ---------- runtime ----------
FROM eclipse-temurin:17-jre-noble AS runtime
WORKDIR /app

# Непривилегированный пользователь
RUN useradd --system --uid 10001 appuser

# *SNAPSHOT.jar — только boot-jar (plain-вариант оканчивается на -plain.jar)
COPY --from=build /workspace/build/libs/*SNAPSHOT.jar app.jar

USER appuser
EXPOSE 8080

# JAVA_OPTS можно передать снаружи: -e JAVA_OPTS="-Xms256m -Xmx512m"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
