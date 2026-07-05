# syntax=docker/dockerfile:1
#
# Multi-stage build for KiraDB.
#
# WHY MULTI-STAGE:
#   The build stage needs the full JDK, the Gradle wrapper, and the entire
#   source tree (including every module's test sources) to compile and
#   assemble the server distribution. None of that belongs in the image we
#   actually run in production — shipping a JDK + build toolchain + source
#   would bloat the final image by hundreds of MB and needlessly widen the
#   attack surface (a stray `javac`/`gradle` binary is not something a
#   database server process needs at runtime). The runtime stage starts
#   from a bare JRE and copies over only the assembled application
#   directory (`installDist` output: launch script + jars), so the shipped
#   image contains exactly what's needed to run `java` against KiraDB's
#   classes and nothing else.
#
# Build:
#   docker build -f docker/Dockerfile -t kiradb/kiradb:latest .
#
# Run:
#   docker run -p 6379:6379 -p 7379:7379 -p 8080:8080 kiradb/kiradb:latest

# ---- Build stage --------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

# Copy Gradle wrapper and build config first so dependency resolution is
# cached across rebuilds that only touch application source.
COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle ./gradle
COPY config ./config
COPY kiradb-core/build.gradle kiradb-core/build.gradle
COPY kiradb-raft/build.gradle kiradb-raft/build.gradle
COPY kiradb-crdt/build.gradle kiradb-crdt/build.gradle
COPY kiradb-server/build.gradle kiradb-server/build.gradle
COPY kiradb-services/build.gradle kiradb-services/build.gradle
COPY kiradb-semantic-cache/build.gradle kiradb-semantic-cache/build.gradle
COPY kiradb-client/build.gradle kiradb-client/build.gradle
COPY kiradb-benchmark/build.gradle kiradb-benchmark/build.gradle

# Now copy full sources.
COPY kiradb-core kiradb-core
COPY kiradb-raft kiradb-raft
COPY kiradb-crdt kiradb-crdt
COPY kiradb-server kiradb-server
COPY kiradb-services kiradb-services
COPY kiradb-semantic-cache kiradb-semantic-cache
COPY kiradb-client kiradb-client
COPY kiradb-benchmark kiradb-benchmark

# Tests already ran in CI (see .github/workflows/ci.yml) — skip them here so
# the image build is just "compile + assemble the runnable distribution".
RUN ./gradlew :kiradb-server:installDist -x test -x checkstyleMain -x checkstyleTest --no-daemon

# ---- Runtime stage -------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime

# Run as a non-root user. Databases handle untrusted network input; a
# container escape or RCE in a dependency should not hand the attacker root
# inside the container. UID/GID are fixed (not auto-assigned) so bind-mounted
# data directories can be chowned predictably from the host. 10001 is used
# instead of the more conventional 1000 because the temurin JRE base image
# already has a GID 1000 (a preinstalled "ubuntu" group), which would make
# `groupadd --gid 1000` fail.
RUN groupadd --gid 10001 kiradb \
    && useradd --uid 10001 --gid kiradb --shell /usr/sbin/nologin --no-create-home kiradb

WORKDIR /app

COPY --from=build /workspace/kiradb-server/build/install/kiradb-server /app

# Default data directory — see kiradb.data.dir system property in
# KiraDBServer.main(). Owned by the kiradb user so LSM/WAL writes succeed
# under a non-root UID even against a bind-mounted host volume.
RUN mkdir -p /data && chown -R kiradb:kiradb /app /data
VOLUME ["/data"]

USER kiradb

# Port 6379 — client-facing RESP3 (redis-cli, Jedis, any Redis client)
# Port 7379 — internal Raft RPCs. NOTE: KiraDBServer.main() does not wire
#             Raft or peer discovery today (see docs/deployment.md and
#             docs/internals/phase12-golive.md) — this port is reserved for
#             when that lands, exposed here so compose files that assume a
#             multi-node cluster don't need a Dockerfile change later.
# Port 8080 — HTTP dashboard/ops JSON API
EXPOSE 6379 7379 8080

# The Gradle `application` plugin's generated launch script (bin/kiradb-server)
# reads the JAVA_OPTS env var and appends it to the JVM command line — this is
# how we pass -D system properties into the container without a custom
# entrypoint script. KiraDBServer.main() reads kiradb.data.dir (default
# ./data, relative to WORKDIR); point it at the mounted volume explicitly.
ENV JAVA_OPTS="-Dkiradb.data.dir=/data"
ENTRYPOINT ["/app/bin/kiradb-server"]
