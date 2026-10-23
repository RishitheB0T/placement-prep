# The backend as a container image, built in two stages.
#
# Stage 1 compiles the jar with the project's own Maven wrapper. Stage 2 starts from a
# plain Java runtime and copies in only that jar, so the final image carries no JDK, no
# Maven and no source code - just what is needed to run.

# ---------------------------------------------------------------- 1. Build
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# The dependencies get a layer of their own, before the source is copied in. They change
# far less often than the code, so on most rebuilds Docker reuses this layer instead of
# downloading every library again.
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src src
# Tests are skipped here, not dropped: they need Docker themselves (Testcontainers), which
# is not available inside an image build. CI and `mvnw test` run them.
RUN ./mvnw -B -q package -DskipTests

# ------------------------------------------------------------------ 2. Run
FROM eclipse-temurin:21-jre
WORKDIR /app

# Run as an ordinary user rather than root. /data holds everything the app writes -
# uploaded resumes, the embedding model, the PyTorch library - and docker-compose mounts
# a volume there so none of it is lost, or downloaded again, when the container is
# replaced.
RUN useradd --system --create-home placementprep \
    && mkdir -p /data/resumes /data/models /data/djl \
    && chown -R placementprep /data
USER placementprep

ENV RESUME_STORAGE_DIR=/data/resumes \
    EMBEDDING_CACHE_DIR=/data/models \
    DJL_CACHE_DIR=/data/djl

COPY --from=build /workspace/target/placementprep-0.0.1-SNAPSHOT.jar app.jar

COPY render-start.sh /app/render-start.sh

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
