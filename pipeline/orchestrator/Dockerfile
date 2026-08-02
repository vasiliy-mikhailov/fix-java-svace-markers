# fsm-orchestrator — the schedule, the queue, the two tables and the run history that n8n used to hold.
#
# THE BUILD CONTEXT IS THE REACTOR ROOT (pipeline/), NOT THIS DIRECTORY. `orchestrator` depends on
# `tech.mikhailov.fsm:engine` and resolves it from the reactor, because nothing publishes that jar to any
# repository. A context of ./orchestrator therefore cannot build this image at all, and the failure
# arrives several minutes into the build as an unresolvable dependency. docker-compose.yml sets
#
#   build: { context: .., dockerfile: orchestrator/Dockerfile }
#
# and by hand it is:
#
#   docker build -f orchestrator/Dockerfile -t fsm-orchestrator:latest .        # from pipeline/
#
# Same shape and same base as ../engine/Dockerfile, for the reason recorded there: start from
# `debian:bookworm-slim` and fetch the toolchain from public sources, so a FRESH CLONE BUILDS. The two
# things that broke when this fleet did otherwise were a base image that existed on one host only, and a
# jar somebody was expected to stage into the build context by hand.
#
# ONE JDK, AND IT IS 25. java-runner carries 8/11/17/21/25 side by side because it BUILDS OTHER PEOPLE'S
# REPOSITORIES; this service only runs its own code, which targets 25.
ARG JDK_VERSION=25
ARG MAVEN_VERSION=3.9.9

# ---- build ---------------------------------------------------------------------------------------
FROM debian:bookworm-slim AS build
ARG JDK_VERSION
ARG MAVEN_VERSION
# Set by buildkit. Following it is what lets this image be verified on a developer's machine: hardcoding
# `linux/x64` builds fine anywhere and then dies at RUN time with "exec format error".
ARG TARGETARCH

RUN apt-get update && apt-get install -y --no-install-recommends \
      curl ca-certificates tar gzip \
    && rm -rf /var/lib/apt/lists/*

# Adoptium, same source and same /opt/jdk/<major> layout as the rest of the fleet, so "where is the JDK"
# has one answer everywhere.
RUN set -eux; \
    case "${TARGETARCH:-amd64}" in \
      amd64) arch=x64 ;; \
      arm64) arch=aarch64 ;; \
      *) echo "unsupported architecture: ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    mkdir -p /opt/jdk/${JDK_VERSION}; \
    curl -fsSL "https://api.adoptium.net/v3/binary/latest/${JDK_VERSION}/ga/linux/${arch}/jdk/hotspot/normal/eclipse?project=jdk" \
      -o /tmp/jdk.tar.gz; \
    tar -xzf /tmp/jdk.tar.gz -C /opt/jdk/${JDK_VERSION} --strip-components=1; \
    rm -f /tmp/jdk.tar.gz; \
    /opt/jdk/${JDK_VERSION}/bin/java -version

RUN set -eux; \
    curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
      -o /tmp/mvn.tar.gz; \
    tar -xzf /tmp/mvn.tar.gz -C /opt; \
    rm -f /tmp/mvn.tar.gz; \
    ln -s /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn

ENV JAVA_HOME=/opt/jdk/${JDK_VERSION}
ENV PATH=/opt/jdk/${JDK_VERSION}/bin:/usr/local/bin:$PATH

# Maven mirror, OPT-IN — exactly as ../engine/Dockerfile explains. This resolves at BUILD time and
# `docker build` does not join a compose network, so a baked-in `http://nexus:8081` mirror makes the
# image unbuildable everywhere, including on the host that has Nexus. Default to Central; pass the mirror
# where it is reachable:
#
#   docker build --network mvn-cache -f orchestrator/Dockerfile \
#     --build-arg MAVEN_MIRROR_URL=http://nexus:8081/repository/maven-public/ -t fsm-orchestrator .
#
# This module has REAL dependencies (Spring Boot), unlike the engine, so a mirror is worth more here: a
# cold build downloads a few hundred artifacts.
ARG MAVEN_MIRROR_URL=
RUN if [ -n "$MAVEN_MIRROR_URL" ]; then \
      mkdir -p /root/.m2; \
      printf '%s\n' \
        '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">' \
        '  <mirrors><mirror>' \
        '    <id>nexus</id><mirrorOf>*</mirrorOf>' \
        "    <url>${MAVEN_MIRROR_URL}</url>" \
        '  </mirror></mirrors>' \
        '</settings>' > /root/.m2/settings.xml; \
    fi

WORKDIR /src
# EVERY MODULE'S POM, not just this module's and its dependency's. Maven builds the model for every module
# the aggregator lists BEFORE `-pl` selects anything, so a missing one fails the build before it starts,
# with "Child module /src/runner of /src/pom.xml does not exist" — a message about a module this image
# does not compile. That is exactly how this file broke when `runner` joined the aggregator, so the rule
# is: a module added to ../pom.xml is a line added here (and in runner/Dockerfile).
#
# The poms first, so a source-only change does not re-resolve the whole tree. `go-offline` is
# best-effort: it fails on plugins that only resolve during a real build, and that must not fail the
# image — the `package` below resolves anything it missed.
COPY pom.xml ./
COPY engine/pom.xml engine/pom.xml
COPY orchestrator/pom.xml orchestrator/pom.xml
COPY runner/pom.xml runner/pom.xml
RUN mvn -B -q -pl orchestrator -am dependency:go-offline || true

COPY engine/src engine/src
COPY orchestrator/src orchestrator/src
# THE ENGINE'S FROZEN DIFFERENTIAL FIXTURES, which are a TEST INPUT and not documentation. `-am` builds
# the engine here, tests included, and three of its tests replay 6 910 cases the retired JavaScript node
# bodies answered — the answers live under engine/harness/fixtures as gzipped data. engine/src alone
# does not build. `harness/out/` stays out (generated, and in .dockerignore); this is 236 KB.
COPY engine/harness/fixtures engine/harness/fixtures

# `-pl orchestrator -am` builds the engine first and the orchestrator against it, from the reactor. This
# is the line the build context exists for.
#
# TESTS RUN IN THE IMAGE BUILD, both modules'. Between them they are the ported specification of which
# markers are retried and which are retired for good, and an image that shipped without running them is
# an image nobody checked. Use `--build-arg SKIP_TESTS=-DskipTests` only to reproduce a build whose
# failure you are already looking at.
ARG SKIP_TESTS=
RUN mvn -B -q -pl orchestrator -am package ${SKIP_TESTS}

# ---- runtime -------------------------------------------------------------------------------------
# Same base and the same JDK, minus Maven and the ~/.m2 tree the build accumulated. The JDK rather than a
# JRE: jcmd, jstack and flight recorder are how a process whose requests last ninety minutes gets
# diagnosed WHILE it is stuck, and adding them afterwards means rebuilding the image that is misbehaving.
FROM debian:bookworm-slim
ARG JDK_VERSION
ARG TARGETARCH

RUN apt-get update && apt-get install -y --no-install-recommends \
      curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN set -eux; \
    case "${TARGETARCH:-amd64}" in \
      amd64) arch=x64 ;; \
      arm64) arch=aarch64 ;; \
      *) echo "unsupported architecture: ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    mkdir -p /opt/jdk/${JDK_VERSION}; \
    curl -fsSL "https://api.adoptium.net/v3/binary/latest/${JDK_VERSION}/ga/linux/${arch}/jdk/hotspot/normal/eclipse?project=jdk" \
      -o /tmp/jdk.tar.gz; \
    tar -xzf /tmp/jdk.tar.gz -C /opt/jdk/${JDK_VERSION} --strip-components=1; \
    rm -f /tmp/jdk.tar.gz; \
    /opt/jdk/${JDK_VERSION}/bin/java -version

ENV JAVA_HOME=/opt/jdk/${JDK_VERSION}
ENV PATH=/opt/jdk/${JDK_VERSION}/bin:$PATH

# Unprivileged, and it OWNS ITS STATE DIRECTORY — which is the one difference from the engine's image.
# This process writes an H2 database, the mount point for it is created here, and the uid is fixed so a
# named volume keeps working across image rebuilds. Without the chown, H2 fails to create the file and
# the service dies on start with a stack trace about a directory nobody looked at.
RUN useradd --system --create-home --uid 10002 orchestrator \
    && mkdir -p /state \
    && chown orchestrator:orchestrator /state
USER orchestrator

COPY --from=build /src/orchestrator/target/fsm-orchestrator.jar /app/fsm-orchestrator.jar

# WHERE THE DATABASE GOES, and the default is deliberately NOT application.yml's.
#
# That one is `./data/fsm`, relative to the working directory — correct for `mvn spring-boot:run` and a
# data-loss bug in a container, because the working directory is a writable layer thrown away with the
# container. A run is 282 markers and 6-26 hours, and losing it is silent: the service starts, serves,
# accepts an ingest and reads zero after the next deploy. So the IMAGE names a path it has prepared, and
# compose mounts a named volume there. Overriding FSM_DB_PATH to somewhere unmounted puts the bug back.
ENV FSM_DB_PATH=/state/fsm

# 8090 is java-runner, 8091 the Node dashboard, 8092 the engine. This one serves the REST triggers, the
# websocket push and the dashboard the orchestrator now hosts itself.
ENV FSM_PORT=8085
EXPOSE 8085

# MaxRAMPercentage rather than a fixed -Xmx: the host runs several stacks and this one's ceiling is
# whatever compose gives it. A fixed heap sized for an uncapped host is a heap the OOM killer takes out
# the moment somebody adds `mem_limit`.
#
# stdout/stderr.encoding, because this image has no locale (LANG is unset in debian-slim) and Java picks
# the platform encoding for the CONSOLE even though file.encoding has defaulted to UTF-8 since 18. The
# lines out of here are not decoration — the realness log, the verdict log and every infra_reason are
# read by grepping them.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

# /healthz runs a real query against the marker table, so this fails when the database is gone rather
# than only when the port is closed. GET, because that is what a HEALTHCHECK can issue without a body.
# start-period is generous: schema.sql and the Batch tables are created on the way up.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${FSM_PORT}/healthz" >/dev/null || exit 1

# No `exec java -jar` through a shell: this process must receive SIGTERM directly so the context shuts
# down and HttpTransport#close stops waiting on in-flight builds. A shell wrapper would swallow it and
# compose would kill the JVM ten seconds later instead.
ENTRYPOINT ["java", "-jar", "/app/fsm-orchestrator.jar"]
