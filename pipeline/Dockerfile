# fsm — THE WHOLE PIPELINE, IN ONE IMAGE.
#
# THE RUNTIME STAGE CARRIES A WHOLE TOOLCHAIN, AND THAT IS NOT OPTIONAL. This process does not merely
# run its own code: it clones repositories and spawns `git`, `mvn` and `./gradlew` as child processes
# (Proc.execFile). An image that shipped the jar without the toolchain would start, serve the dashboard,
# answer /healthz — and report every marker in the backlog as an infrastructure failure.
#
# ONE IMAGE IS ALSO ONE FEWER ADDRESS. The prove runs in this process (LocalRunner: the same Prove,
# Workspace and Build behind the same single-flight queue). A separate prover has to be addressed, and
# that address lives in three places — the compose environment, application.yml and
# HttpRunnerClient.DEFAULT_BASE_URL, each a fallback for the one above it — none of them read until a
# marker is proved, so a wrong one surfaces hours into a 6-26 hour run as a refused connect filed as an
# infrastructure failure. `FSM_RUNNER_MODE=http` still selects the split shape, for a deployment that
# wants the build sandbox behind a container boundary.
#
# THE PROVISIONING BELOW IS THE EXPENSIVE, FRAGILE PART. Each line of it is load-bearing:
#
#   * FROM debian:bookworm-slim AND FETCH FROM PUBLIC SOURCES. Not from a base image built elsewhere on
#     the host, and no jar staged into the build context by hand. Both made the image unbuildable on a
#     clean checkout — "pull access denied" for a local-only base, "/nav/lib not found" for jars that
#     live in another repo. A FRESH CLONE MUST BUILD.
#   * JDKs 8/11/17/21/25 SIDE BY SIDE at /opt/jdk/<major>. Not five for luck: Build.requiredJdk reads
#     "release version N not supported" out of a failed build and Prove retries on N, so every major it
#     may switch to has to be present or the retry silently declines and the marker is filed as
#     unreproducible on a JDK it was never going to compile under. WebGoat needs 25. GET /health-style
#     reporting lists what is actually on disk, so a failed download shows up as a missing JDK.
#   * TARGETARCH-aware. A hardcoded `linux/x64` is correct on one host and silently wrong everywhere
#     else: the image builds and every `java` then dies at RUN time with "exec format error". This is
#     how the image is verifiable on an arm64 laptop.
#   * GRADLE IS DELIBERATELY ABSENT. Build.buildCmd emits `./gradlew`, never `gradle`, so every Gradle
#     project is built by the wrapper it pins. A Gradle distribution here would be one nothing executes.
#
# AND ONE THING THAT IS DELIBERATELY ABSENT — a committed settings.xml. See the runtime stage.
#
# THE BUILD CONTEXT IS THE REACTOR ROOT (pipeline/), which is where this file lives. `orchestrator`
# depends on `engine` AND on `runner` and resolves both FROM THE REACTOR, because nothing publishes
# those jars to any repository. By hand, from pipeline/:
#
#   docker build -t fsm:latest .
#
ARG JDK_VERSION=25
ARG MAVEN_VERSION=3.9.9

# ---- build ---------------------------------------------------------------------------------------
# ONE JDK here, and it is 25: this stage compiles this project's own code, which targets 25 via
# maven.compiler.release / spring-boot-starter-parent's java.version. The five-JDK toolchain belongs to
# the runtime stage, where other people's repositories are built.
FROM debian:bookworm-slim AS build
ARG JDK_VERSION
ARG MAVEN_VERSION
ARG TARGETARCH

RUN apt-get update && apt-get install -y --no-install-recommends \
      curl ca-certificates tar gzip \
    && rm -rf /var/lib/apt/lists/*

# THIS IMAGE IS NOT REPRODUCIBLE, AND THE REASON IS THE NEXT `curl`. `…/v3/binary/latest/${N}/ga/…` is a
# MOVING TARGET: two builds of this file a week apart get different 25.0.x, with no checksum and no
# signature checked on the way in. It is written down here rather than fixed because the fix is an
# addition rather than a deletion and nobody has costed the upkeep:
#
#   https://api.adoptium.net/v3/binary/version/jdk-25.0.4+7/linux/${arch}/jdk/hotspot/normal/eclipse
#
# pins one release, and `/v3/assets/latest/${N}/hotspot?…` serves that release's sha256 so the tarball
# can be verified rather than trusted. THE UPKEEP IS THE PART TO DECIDE FIRST: the runtime stage below
# takes FIVE majors on TWO architectures, so a properly pinned-and-checksummed image is ten release
# names and ten hashes that someone has to bump on every Adoptium release, and a stale pin is a JDK
# nobody is patching. `latest` is the opposite trade, taken by default rather than on purpose.
#
# (JDK_VERSION stays the MAJOR either way — it is also the /opt/jdk/<major> directory name that
# runner/Build.javaHome resolves against, and DeploymentTest pins it at 25.)
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

# A LOCALE, AND ONLY IN THIS STAGE — where the runtime stage below refuses one at length. The two are
# not in tension: that refusal is about the builds of THIRD-PARTY REPOSITORIES the running service
# performs, and this stage builds nothing but this project and runs nothing but its own tests.
#
# It is required, not tidiness. debian-slim has no locale, so glibc reports ANSI_X3.4-1968 and the JDK
# derives `sun.jnu.encoding` from it — the property java.nio.file.Path encodes filenames with, which is
# NOT `file.encoding` (UTF-8 on every platform since JDK 18, which is why this looked fine). The
# runner's differential harness replays 128 frozen cases whose workspace is `/cache/é😀`, so with an
# ASCII sun.jnu.encoding the suite cannot even construct the path it is asked about:
#
#   java.nio.file.InvalidPathException: Malformed input or input contains unmappable characters: /cache/??
#       at sun.nio.fs.UnixPath.encode(UnixPath.java:131)
#
# and the image build dies there. `-Dsun.jnu.encoding=UTF-8` does NOT fix it — the JDK overwrites the
# command line with the platform's value ("Platform defined encodings cannot be overridden on the
# command line", jdk.internal.util.SystemProps) — so the locale is the only lever. PathEncodingTest
# measures both halves of that claim; RunnerImageTest asserts this line and its counterpart below.
ENV LANG=C.UTF-8

# THE BUILD-TIME Maven mirror, OPT-IN. This stage resolves at BUILD time and `docker build` does not
# join a compose network, so a baked-in `http://nexus:8081` mirror makes the image unbuildable
# everywhere, including on the host that has Nexus. Default to Central; pass the mirror where it is
# reachable:
#
#   DOCKER_BUILDKIT=0 docker build --network mvn-cache \
#     --build-arg MAVEN_MIRROR_URL=http://nexus:8081/repository/maven-public/ -t fsm:latest .
#
# THIS IS NOT THE MIRROR THE PROVES USE. That one is the MAVEN_MIRROR_URL *environment variable* on the
# running container, read on the way up by MavenSettings — see the runtime stage. The two are separate
# on purpose: the Guild will run an image they did not build, against a Nexus at their own endpoint,
# and a value baked in here would be no use to them at all.
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
# ALL FOUR POMS. Maven builds the model for EVERY module the aggregator lists before `-pl` selects
# anything, so a missing one fails the build before it starts with "Child module /src/x of /src/pom.xml
# does not exist" — a message about a module this image does not even compile. A module added to
# pom.xml is a line added here.
#
# The poms alone first, so a source-only change does not re-resolve the tree. `go-offline` is
# best-effort: it fails on plugins that only resolve during a real build, and that must not fail the
# image — the `package` below resolves anything it missed.
COPY pom.xml ./
COPY engine/pom.xml engine/pom.xml
COPY orchestrator/pom.xml orchestrator/pom.xml
COPY runner/pom.xml runner/pom.xml
RUN mvn -B -q -pl orchestrator -am dependency:go-offline || true

COPY engine/src engine/src
COPY runner/src runner/src
COPY orchestrator/src orchestrator/src
# THE FROZEN DIFFERENTIAL FIXTURES OF BOTH MODULES, which are a TEST INPUT and not documentation. The
# runner's DifferentialHarnessTest replays 23 401 cases and the recorded reference answer to each —
# cases, answers and the filesystem tree four families read — and the engine's three tests do the same
# for its decision classes. src/ alone does not build. `harness/out/` stays out (generated, and in
# .dockerignore).
COPY engine/harness/fixtures engine/harness/fixtures
COPY runner/harness/fixtures runner/harness/fixtures

# `-pl orchestrator -am` builds engine, then runner, then the orchestrator against both, from the
# reactor. This is the line the build context exists for: `runner` is a compile dependency of
# `orchestrator`, because the prove runs in-process.
#
# TESTS RUN IN THE IMAGE BUILD, all three modules'. Between them they are the specification of the one
# distinction the whole pipeline rests on — did the test RUN and fail, or did it never run —
# and of which markers are retried and which are retired for good. An image that shipped without running
# them is an image nobody checked. Use `--build-arg SKIP_TESTS=-DskipTests` only to reproduce a build
# whose failure you are already looking at.
ARG SKIP_TESTS=
RUN mvn -B -q -pl orchestrator -am package ${SKIP_TESTS}

# ---- runtime -------------------------------------------------------------------------------------
# THE WHOLE TOOLCHAIN LIVES HERE, and none of it is optional: /run_test spawns `git`, `mvn` and `./gradlew` as child processes over the repositories under
# analysis, so stripping any of it out produces an image that starts, answers /healthz, and then reports
# every marker in the backlog as an infrastructure failure.
FROM debian:bookworm-slim
ARG JDK_VERSION
ARG MAVEN_VERSION
ARG TARGETARCH

# git — every prove starts with a clone. procps, tar, gzip, unzip, xz-utils — build scripts in the
# repositories under test shell out to them, and `ps`/`kill` are how a Maven wedged inside its
# twenty-minute ceiling gets looked at from `docker exec`. curl is the HEALTHCHECK's.
RUN apt-get update && apt-get install -y --no-install-recommends \
      curl ca-certificates git procps tar gzip unzip xz-utils \
    && rm -rf /var/lib/apt/lists/*

# --- JDKs 8/11/17/21/25 side by side at /opt/jdk/<major> -------------------------------------------
# Build.JDKS is this list and Build.javaHome is this path; the two are checked against each other by the
# runner's own suite. The prove picks one per build from the request and RE-RUNS with a different one
# when a build reports a release-version mismatch, so every major it may switch to has to be present.
#
# UNPINNED, same as the build stage's single JDK — see the paragraph there for the exact pinned URL, the
# checksum endpoint, and why the upkeep is the decision rather than the edit. This loop is the reason it
# is ten hashes and not two.
RUN set -eux; \
    case "${TARGETARCH:-amd64}" in \
      amd64) arch=x64 ;; \
      arm64) arch=aarch64 ;; \
      *) echo "unsupported architecture: ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    for v in 8 11 17 21 25; do \
      mkdir -p /opt/jdk/$v; \
      curl -fsSL "https://api.adoptium.net/v3/binary/latest/${v}/ga/linux/${arch}/jdk/hotspot/normal/eclipse?project=jdk" \
        -o /tmp/jdk.tar.gz; \
      tar -xzf /tmp/jdk.tar.gz -C /opt/jdk/$v --strip-components=1; \
      rm -f /tmp/jdk.tar.gz; \
      /opt/jdk/$v/bin/java -version; \
    done

# --- Maven ------------------------------------------------------------------------------------------
# NO settings.xml IS COPIED, AND THAT ABSENCE IS LOAD-BEARING — it is what makes this image usable on a
# machine that is not the one it was built on.
#
# A copied settings.xml pinning `mirrorOf=*` at some internal host is not a cache in front of Central —
# it is the ONLY repository Maven will talk to. On any machine where that hostname does not resolve, the
# container starts, answers /health with all five JDKs, clones the repository under test, and then fails
# EVERY build in hundreds of lines about unresolvable artifacts. That reads as a broken project, not as
# an image carrying somebody else's infrastructure.
#
# WHERE IT WENT: MavenSettings, read at RUN TIME from the MAVEN_MIRROR_URL environment variable.
#   unset  -> no settings file, no -s, Maven resolves from Central. The default, and it must work on a
#             laptop with nothing but Docker, because it is the first thing a new reader will do.
#   set    -> a settings.xml written onto the cache volume and handed to every `mvn` with -s.
# An environment variable rather than a build argument, because the Guild will run an image they did not
# build against a Nexus at their own endpoint.
RUN set -eux; \
    curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
      -o /tmp/mvn.tar.gz; \
    tar -xzf /tmp/mvn.tar.gz -C /opt; \
    rm -f /tmp/mvn.tar.gz; \
    ln -s /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn

# JAVA_HOME is 25: this jar is compiled with `release 25` and a 21 launcher refuses it with an
# UnsupportedClassVersionError on start. It does not leak into a build under test — Build.buildCmd sets
# JAVA_HOME and prepends /opt/jdk/<major>/bin to PATH for every child, from the request's `jdk` (default
# 17) — so what this pair governs is this process and a human's `docker exec fsm mvn -v`.
ENV JAVA_HOME=/opt/jdk/${JDK_VERSION}
ENV PATH=/opt/jdk/${JDK_VERSION}/bin:/opt/apache-maven-${MAVEN_VERSION}/bin:/usr/local/bin:$PATH

# UNPRIVILEGED, AND IT OWNS BOTH ITS DIRECTORIES. This process writes an H2 database AND runs arbitrary
# build scripts out of arbitrary repositories. It must not run either as root.
#
# THE UID IS DATA-RETENTION CONFIG, NOT TASTE. /state holds the live H2 store on a named volume created
# under 10002, and feedback/ on the deployment host is chowned to 10002 by hand (orchestrator/README.md
# says so, and so does the compose file). Change this number and the running deployment can no longer
# open its own database or write its feedback file. A FRESH volume is fine either way — Docker seeds an
# empty named volume from the image's directory, ownership included — which is exactly why the breakage
# would only show up where the data already is.
#
# HOME is set explicitly rather than left to a passwd lookup, because `mvn` reads $HOME to find ~/.m2
# and `./gradlew` reads it to find ~/.gradle; unset, both land in / and fail as root-only.
RUN useradd --system --create-home --uid 10002 fsm \
    && mkdir -p /state /cache \
    && chown -R fsm:fsm /state /cache /home/fsm
USER fsm
ENV HOME=/home/fsm

# The Spring Boot fat jar: the orchestrator, the engine and the runner in one archive. There is no
# /app/libs to keep in step with it — `spring-boot-maven-plugin` repackages every runtime dependency, so
# a dependency added to a pom is carried by the image rather than discovered as a NoClassDefFoundError
# on the first container start.
COPY --from=build /src/orchestrator/target/fsm-orchestrator.jar /app/fsm.jar

# 8085 — the REST triggers, the websocket push and the dashboard. (8092 is the optional engine's; 8090
# is the prover's, and is published only in the split shape, by a container you start yourself.)
ENV FSM_PORT=8085
EXPOSE 8085

# WHERE THE DATABASE GOES, and the default is deliberately NOT application.yml's. That one is
# `./data/fsm`, relative to the working directory — correct for `mvn spring-boot:run` and a data-loss
# bug in a container, because the working directory is a writable layer thrown away with the container.
# A run is 282 markers and 6-26 hours, and losing it is silent. So the IMAGE names a path it has
# prepared, and compose mounts a named volume there.
ENV FSM_DB_PATH=/state/fsm

# …AND THE SAME ARGUMENT FOR THE CHECKOUTS. Unmounted, /cache is the container's writable layer, so every `docker compose up -d` throws away every
# checkout and every target/ behind it — minutes per repository, silently, on a run that is 282 markers
# long. application.yml defaults fsm.runner.cache to ./data/cache, which is laptop-correct and wrong
# here; DeploymentTest pins this variable to the volume mounted on it.
ENV CACHE=/cache

# NO LANG / LC_ALL AS AN IMAGE ENV, and this omission is a decision rather than an oversight — the
# tempting one-liner is `ENV LANG=C.UTF-8`, on the argument that debian-slim has no locale and a Maven
# log with a non-ASCII path could arrive mangled into the text Build greps for "Tests run:".
#
# It is refused because a locale set HERE is inherited by every build of every repository under test,
# and for a JDK 8 or 11 build it changes `file.encoding` from ASCII to UTF-8 — which changes what those
# tests DO. This service exists to answer whether a defect reproduces, so a deployment detail must not
# quietly become a variable in the verdict.
#
# BUT THE WRITING SIDE NEEDS A LOCALE. `java.nio.file.Path` is not characters — it is the bytes the
# kernel takes — and the JDK produces them with `sun.jnu.encoding`, derived from THIS PROCESS'S LOCALE.
# With none it is ANSI_X3.4-1968 and no filename outside ASCII can be named from Java at all: an edit
# aimed at `…/Café.java` came back "file not found", a non-ASCII `test_path` threw InvalidPathException
# out of /run_test and was retried for ever, and the dashboard reported "source unavailable" for a file
# in the checkout. `-Dsun.jnu.encoding=UTF-8` does NOT set it either.
#
# SO THE LOCALE IS SET ON THE EXEC LINE BELOW, for one process — and Proc.isLocale then removes LANG,
# LANGUAGE and every LC_* from every child it starts, so a build under test sees an empty locale however
# this process was started. That is guaranteed by code a test pins
# (ProcTest.theRunnersOwnLocaleIsStrippedFromEveryChild) rather than by the absence of a line here,
# which is what makes it survive somebody adding an `environment:` entry in compose.

# /healthz runs a real query against the marker table, so this fails when the database is gone rather
# than only when the port is closed. GET, because that is what a HEALTHCHECK can issue without a body.
# start-period is generous: schema.sql and the Batch tables are created on the way up.
#
# IT STAYS HONEST DURING A PROVE. A prove blocks a single build thread for up to 90 minutes; the web
# layer is Boot's and is unaffected, so a busy pipeline is never indistinguishable from a dead one.
# `restart: unless-stopped` reacts to the process exiting, not to this probe, so an unhealthy report
# never kills a build by itself.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${FSM_PORT}/healthz" >/dev/null || exit 1

# `env LC_ALL=C.UTF-8` AND THEN `java`, which is three things at once:
#
#   * it is the ONLY way to set sun.jnu.encoding (see above), and it reaches this process because a
#     variable placed before a command is that command's environment and nothing else's;
#   * it is NOT an image ENV, so no `docker exec` and no compose `environment:` carries it, and the
#     builds under test are unaffected — Proc strips it from every child regardless;
#   * `env` EXECS the command rather than forking it, so the JVM keeps pid 1 and receives compose's
#     SIGTERM directly. That matters here: the shutdown closes the Spring context, which closes
#     LocalRunner, which interrupts the build thread and kills the child Maven rather than orphaning one
#     that owns the shared workspace. A shell wrapper would swallow the signal and compose would SIGKILL
#     the JVM ten seconds later.
#
# THE JVM FLAGS ARE ON THE COMMAND LINE, NOT IN JAVA_TOOL_OPTIONS. JAVA_TOOL_OPTIONS would be inherited
# by every child JVM — every Maven, plus each surefire JVM it forks, running somebody else's tests. They
# would all pick up this process's heap ceiling and print "Picked up JAVA_TOOL_OPTIONS:" into the very
# log Build.summarize parses. Flags after `java` reach this JVM and stop there.
#
# 25%, and not more, because this JVM shares its container with the builds it exists to run: a 90-minute
# prove is a Maven and its forked test JVMs, which need most of the memory. A heap allowed to grow to
# three quarters of the container would win that race and get the build OOM-killed — reported as an
# infrastructure failure against a marker that was fine.
#
# stdout/stderr.encoding because the container has no console encoding to inherit, and these lines —
# the realness log, the verdict log, every infra_reason — are read by grepping them.
ENTRYPOINT ["env", "LC_ALL=C.UTF-8", \
            "java", \
            "-XX:MaxRAMPercentage=25", \
            "-Dstdout.encoding=UTF-8", \
            "-Dstderr.encoding=UTF-8", \
            "-jar", "/app/fsm.jar"]
