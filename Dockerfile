# syntax=docker/dockerfile:1
# One image serves the React app and the API on one origin (see SpaConfig).
#   docker build -t pulseguard .
# Tests are not run here: they need Docker themselves (Testcontainers). Run ./mvnw test first.

# ---- 1. Frontend: static files in dist/ ----
FROM node:22 AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY frontend/ ./
RUN npm run build

# ---- 2. Backend: the jar, with the frontend inside it, split into layers ----
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn .mvn
# Dependencies first: this layer is reused until pom.xml changes. The cache mount keeps
# ~/.m2 between builds even when it is not.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src src
COPY --from=frontend /frontend/dist/ src/main/resources/static/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
    && cp target/*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---- 3. Runtime: JRE only, non-root ----
FROM eclipse-temurin:21-jre
RUN groupadd --system pulseguard && useradd --system --gid pulseguard --no-create-home pulseguard
WORKDIR /app
# Least to most often changed, so a code change rebuilds only the last small layer.
COPY --from=backend /build/extracted/dependencies/ ./
COPY --from=backend /build/extracted/spring-boot-loader/ ./
COPY --from=backend /build/extracted/snapshot-dependencies/ ./
COPY --from=backend /build/extracted/application/ ./
USER pulseguard

# The heap is sized to the container's memory limit, not the host's.
ENV SPRING_PROFILES_ACTIVE=prod \
    SERVER_PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

# Liveness only: a database or Redis outage must not get the container restarted.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS "http://localhost:${SERVER_PORT}/actuator/health/liveness" || exit 1

ENTRYPOINT ["java", "-jar", "application.jar"]
