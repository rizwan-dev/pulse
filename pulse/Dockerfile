# -jammy, not -alpine: eclipse-temurin publishes its Alpine variants for amd64
# only at 17, so an Alpine base cannot build on Apple Silicon or arm64 runners.
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /build

# Dependency resolution is its own layer, cached until the build files change.
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src/ src/
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --shell /usr/sbin/nologin app
USER app

COPY --from=build /build/build/install/pulse/ /app/

EXPOSE 9191
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=10 \
  CMD curl -fsS http://localhost:9191/health || exit 1

ENTRYPOINT ["/app/bin/pulse"]
