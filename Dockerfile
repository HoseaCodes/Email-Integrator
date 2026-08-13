# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------------------------
# Build stage
# ---------------------------------------------------------------------------------------------
# The previous Dockerfile required `mvn package` to have been run on the host first and then
# copied target/*.jar in. That made the image unreproducible from source — what you got depended
# on whatever happened to be in target/ — and meant a stale jar could be shipped silently.
# Building inside the image makes `docker build .` sufficient and deterministic.
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

# Dependencies resolve in their own layer, keyed only on the POM. Source changes — the common
# case — reuse the cached layer instead of re-downloading the dependency tree every build.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src

# Tests run in CI against a clean checkout, so re-running them here would double the build time
# for no additional signal. The trade-off: `docker build` alone does not verify the code. CI is
# the gate; this stage only packages.
RUN mvn -B -q clean package -DskipTests

# ---------------------------------------------------------------------------------------------
# Runtime stage
# ---------------------------------------------------------------------------------------------
# JRE, not JDK. The previous image shipped a full compiler toolchain plus the application source
# into production — a larger attack surface and a larger image for no runtime benefit.
FROM eclipse-temurin:17-jre AS runtime

# Run as an unprivileged user. The previous image ran as root, so a remote-code-execution bug
# would have started with full control of the container rather than needing an escalation step.
# A fixed uid/gid keeps file ownership predictable when a volume is mounted.
RUN groupadd --system --gid 10001 appuser \
 && useradd --system --uid 10001 --gid appuser --home-dir /app --shell /usr/sbin/nologin appuser

WORKDIR /app

# Only the jar. No source, no build tooling, no POM.
COPY --from=build --chown=appuser:appuser /build/target/*.jar app.jar

USER appuser

# Documentation only — publishing the port is the caller's decision. Matches server.port.
EXPOSE 8082

# Reports the container unhealthy if the application stops answering, which is what lets an
# orchestrator restart or replace it. start-period covers JVM and Spring context startup so a
# slow boot is not mistaken for a failure.
#
# curl ships in the eclipse-temurin JRE image, so no extra packages are installed for this.
# --fail makes a non-2xx status a non-zero exit; /actuator/health is deliberately public so the
# probe needs no credentials.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl --fail --silent --show-error http://127.0.0.1:8082/actuator/health || exit 1

# exec form, so the JVM is PID 1 and receives SIGTERM directly. With the shell form it would be a
# child of /bin/sh, which does not forward signals — the JVM would never run its shutdown hooks
# and every stop would be a 10-second timeout followed by SIGKILL, dropping in-flight requests.
#
# -XX:MaxRAMPercentage sizes the heap from the container's memory limit rather than the host's
# total RAM, which is what makes the JVM behave sensibly under a cgroup constraint.
#
# -Djava.security.egd=file:/dev/./urandom avoids a stall on container startup when the entropy
# pool is shallow and the default blocking source has nothing to give.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
