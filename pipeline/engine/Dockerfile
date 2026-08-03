# fsm-engine — the judgement, published one stage per request. PROFILE-GATED: nothing in a run calls it
# (the pipeline embeds this module as a library), and it exists so a single stage can be replayed
# against a request written by hand. See deploy/docker-compose.yml.
#
# START FROM `debian:bookworm-slim` AND FETCH THE TOOLCHAIN FROM PUBLIC SOURCES, so a FRESH CLONE
# BUILDS. A base image that exists only on one host fails with "pull access denied", and a jar somebody
# is expected to stage into the build context by hand fails on a missing path. Neither is repeatable,
# and both fail at the worst moment: on a new machine.
#
# ONE JDK, NOT FIVE. The pipeline image carries 8/11/17/21/25 side by side because it BUILDS OTHER
# PEOPLE'S REPOSITORIES and re-runs with a different major when javac reports a release-version
# mismatch. This service only ever runs its own code, which targets 25, so the other majors would be
# ~1.5 GB of image nothing can reach.
ARG JDK_VERSION=25
ARG MAVEN_VERSION=3.9.9

# ---- build ---------------------------------------------------------------------------------------
FROM debian:bookworm-slim AS build
ARG JDK_VERSION
ARG MAVEN_VERSION
# Set by buildkit. A hardcoded `linux/x64` is correct on one host and silently wrong anywhere else: the
# image builds, and every `java` call then dies with "exec format error" at RUN time. Following
# TARGETARCH is what lets this be verified on a developer's machine.
ARG TARGETARCH

RUN apt-get update && apt-get install -y --no-install-recommends \
      curl ca-certificates tar gzip \
    && rm -rf /var/lib/apt/lists/*

# Adoptium, same source and same /opt/jdk/<major> layout as the pipeline image, so "where is the JDK"
# has one answer in both.
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

# Maven mirror, OPT-IN. This stage resolves at BUILD time and `docker build` does not join a compose
# network, so a baked-in internal mirror makes the image unbuildable everywhere — including on the host
# that runs the mirror. Default to Central; pass the mirror where it is reachable:
#
#   docker build --network <net> \
#     --build-arg MAVEN_MIRROR_URL=http://nexus:8081/repository/maven-public/ -t fsm-engine .
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
# The pom alone first, so a source-only change does not re-resolve the dependency tree. `go-offline` is
# best-effort: it fails on plugins that only resolve during a real build, and that must not fail the
# image — the `package` below resolves anything it missed.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline || true

COPY src ./src
# THE FROZEN DIFFERENTIAL FIXTURES, which are a TEST INPUT and not documentation. Three of this
# module's tests replay 6 910 cases and the recorded reference answer to each, and those answers live
# here as gzipped data — src/test alone does not build. `harness/out/` stays out (it is generated and is
# in .dockerignore); `harness/fixtures/` is 236 KB and has to be in the context.
COPY harness/fixtures ./harness/fixtures
# Tests run IN THE IMAGE BUILD. This module decides whether a marker is retried or retired for good,
# and the suite is the specification of those rules — an image that ships without having run it
# is an image nobody checked. Use `--build-arg SKIP_TESTS=-DskipTests` only to reproduce a build whose
# failure you are already looking at.
ARG SKIP_TESTS=
RUN mvn -B -q package ${SKIP_TESTS}

# ---- runtime -------------------------------------------------------------------------------------
# Same base and the same JDK, minus Maven and the ~/.m2 tree the build accumulated. The JDK rather than
# a JRE: Adoptium's `jre` package is smaller, but jcmd, jstack and flight recorder are how a service
# whose requests can last an hour gets diagnosed WHILE it is stuck, and adding them after the fact
# means rebuilding the image that is currently misbehaving.
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

# Unprivileged: the engine reads a request and writes a reply. It has no repository checkout, no docker
# socket and nothing to write to disk, so there is nothing root is needed for.
RUN useradd --system --create-home --uid 10001 engine
USER engine

COPY --from=build /src/target/fsm-engine.jar /app/fsm-engine.jar

# Published on 127.0.0.1:8092 by the compose profile, because reaching it by hand IS what it is for.
ENV PORT=8092
EXPOSE 8092

# MaxRAMPercentage rather than a fixed -Xmx: the host runs several stacks and this one's ceiling is
# whatever the compose file gives it. A fixed heap sized for an uncapped host is a heap the OOM killer
# takes out the moment somebody adds `mem_limit`.
#
# stdout/stderr.encoding, because this image has no locale (LANG is unset in debian-slim) and Java
# picks the platform encoding for the CONSOLE even though the file.encoding default has been UTF-8
# since 18. The lines that come out of here are not decoration: NodeRoutes returns each stage's log line
# in its reply, and without this `[realness] … no stubbing at all — drives the real objects end to end`
# arrives as `at all ? drives`. An operator greps these.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

# GET, because that is what a HEALTHCHECK can issue without a body. The endpoint accepts POST too.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${PORT}/health" >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/fsm-engine.jar"]
