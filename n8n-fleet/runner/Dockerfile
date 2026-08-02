# fsm-runner — the prover's hands, as a Java service. It replaces ../java-runner (Node) route for route.
#
# WHAT IS ACTUALLY DIFFERENT FROM ../java-runner/Dockerfile, and it is one thing: the process this image
# starts is a jar instead of `node /app/src/server.js`. EVERYTHING ELSE IS THE SAME PROBLEM, because
# /run_test does not compute an answer — it SHELLS OUT. `git clone`, then `mvn` or `./gradlew`, twice,
# against somebody else's repository. So the runtime stage of this image is not "a JRE plus a jar": it is
# the whole toolchain the JS image provisioned, and that provisioning is the expensive, fragile, already
# correct part. It is carried over here verbatim in intent and near-verbatim in text:
#
#   * FROM debian:bookworm-slim AND FETCH FROM PUBLIC SOURCES. Not from a base image built elsewhere on
#     the host (the parent project's `bjv-alljdk`), and no jar staged into the build context by hand.
#     Both made the image unbuildable on a clean checkout — `docker compose build` failed with "pull
#     access denied" for a local-only base, and with "/nav/lib not found" for jars that live in another
#     repo. A FRESH CLONE MUST BUILD, and that is the property both of those broke.
#   * JDKs 8/11/17/21/25 SIDE BY SIDE at /opt/jdk/<major>. Not a style choice and not five for luck:
#     Build.requiredJdk reads "release version N not supported" out of a failed build and Prove retries
#     on N, so every major it may switch to has to be present or the retry silently declines (see
#     Build.JDKS — the list in the code and the loop below are the same five, and /health reports what is
#     actually on disk so a failed download cannot masquerade as a JDK this runner has).
#   * Maven 3.9.9 plus settings.xml with mirrorOf=*. NOT a cache in front of Central — the ONLY
#     repository Maven will talk to. See the runtime stage.
#   * GRADLE IS DELIBERATELY ABSENT, exactly as in ../java-runner/Dockerfile. Every Gradle project the
#     runner builds is invoked through its own ./gradlew (Build.buildCmd emits `./gradlew`, never
#     `gradle`), which downloads the version that project pins. A Gradle distribution in this image
#     would be one nothing ever executes.
#
# THE BUILD CONTEXT IS THE REACTOR ROOT (n8n-fleet/), NOT THIS DIRECTORY — the same constraint
# ../orchestrator/Dockerfile records, and this file matches it. `runner` depends on
# tech.mikhailov.fsm:engine and resolves it FROM THE REACTOR, because nothing publishes that jar to any
# repository. A context of ./runner cannot build this image at all, and the failure arrives minutes in as
# an unresolvable dependency. docker-compose.yml therefore sets
#
#   build: { context: .., dockerfile: runner/Dockerfile }
#
# and by hand it is:
#
#   docker build -f runner/Dockerfile -t fsm-runner:latest .        # from n8n-fleet/
#
ARG JDK_VERSION=25
ARG MAVEN_VERSION=3.9.9

# ---- build ---------------------------------------------------------------------------------------
# ONE JDK here, and it is 25: this stage compiles this project's own code, which targets 25 via
# maven.compiler.release. The five-JDK toolchain belongs to the runtime stage, where other people's
# repositories are built.
FROM debian:bookworm-slim AS build
ARG JDK_VERSION
ARG MAVEN_VERSION
# Set by buildkit. java-runner hardcoded `linux/x64`, which is correct on the deployment host and
# silently wrong anywhere else: the image builds and every `java` then dies at RUN time with "exec
# format error". Following TARGETARCH is what lets this image be verified on a developer's machine, and
# it is the one place engine/ and orchestrator/ improved on the file this one inherits from.
ARG TARGETARCH

RUN apt-get update && apt-get install -y --no-install-recommends \
      curl ca-certificates tar gzip \
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

RUN set -eux; \
    curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
      -o /tmp/mvn.tar.gz; \
    tar -xzf /tmp/mvn.tar.gz -C /opt; \
    rm -f /tmp/mvn.tar.gz; \
    ln -s /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn

ENV JAVA_HOME=/opt/jdk/${JDK_VERSION}
ENV PATH=/opt/jdk/${JDK_VERSION}/bin:/usr/local/bin:$PATH

# A LOCALE, AND ONLY IN THIS STAGE — where the runtime stage below refuses one for reasons it spells out
# at length. The two are not in tension: that refusal is about the builds of THIRD-PARTY REPOSITORIES the
# running service performs, and this stage builds nothing but `engine` and `runner` and runs nothing but
# their own tests.
#
# It is required, not tidiness. debian-slim has no locale, so glibc reports ANSI_X3.4-1968 and the JDK
# derives `sun.jnu.encoding` from it — the property java.nio.file.Path encodes filenames with, which is
# NOT `file.encoding` (UTF-8 on every platform since JDK 18, which is why this looked fine). The runner's
# differential harness replays 128 frozen cases whose workspace is `/cache/é😀`, so with an ASCII
# sun.jnu.encoding the suite cannot even construct the path it is asked about:
#
#   java.nio.file.InvalidPathException: Malformed input or input contains unmappable characters: /cache/??
#       at sun.nio.fs.UnixPath.encode(UnixPath.java:131)
#
# and the image build dies there. `-Dsun.jnu.encoding=UTF-8` does NOT fix it — the JDK overwrites the
# command line with the platform's value ("Platform defined encodings cannot be overridden on the command
# line", jdk.internal.util.SystemProps) — so the locale is the only lever there is. PathEncodingTest
# measures both halves of that claim; RunnerImageTest asserts this line and its counterpart below.
ENV LANG=C.UTF-8

# Maven mirror, OPT-IN — identical to engine/ and orchestrator/, and this is the one thing that must NOT
# be copied from java-runner's unconditional settings.xml. This stage resolves at BUILD time and
# `docker build` does not join a compose network, so a baked-in `http://nexus:8081` mirror makes the
# image unbuildable everywhere, including on the host that has Nexus. Default to Central; pass the mirror
# where it is reachable:
#
#   docker build --network mvn-cache -f runner/Dockerfile \
#     --build-arg MAVEN_MIRROR_URL=http://nexus:8081/repository/maven-public/ -t fsm-runner:latest .
#
# The RUNTIME stage is the opposite case and keeps java-runner's unconditional mirror; see there.
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
# ALL FOUR POMS, and the third one is not padding. Maven builds the model for EVERY module the
# aggregator lists before `-pl` selects anything, so a missing orchestrator/pom.xml fails the build
# before it starts, with "Child module /src/orchestrator of /src/pom.xml does not exist" — nothing to do
# with the module being built. (This is a live trap: ../orchestrator/Dockerfile predates the `runner`
# module in the aggregator and needed runner/pom.xml added for exactly this reason.)
#
# The poms alone first, so a source-only change does not re-resolve the tree. `go-offline` is
# best-effort: it fails on plugins that only resolve during a real build, and that must not fail the
# image — the `package` below resolves anything it missed.
COPY pom.xml ./
COPY engine/pom.xml engine/pom.xml
COPY orchestrator/pom.xml orchestrator/pom.xml
COPY runner/pom.xml runner/pom.xml
RUN mvn -B -q -pl runner -am dependency:go-offline || true

# orchestrator/src is NOT copied: `-pl runner -am` builds engine and runner only, and the orchestrator's
# sources would be a cache-busting 1 MB this image never compiles.
COPY engine/src engine/src
COPY runner/src runner/src
# THE FROZEN DIFFERENTIAL FIXTURES OF BOTH MODULES, which are a TEST INPUT and not documentation. The
# runner's DifferentialHarnessTest replays the 23 851 cases the retired java-runner answered — cases,
# answers and the filesystem tree four families read — and the engine's three do the same for its node
# bodies. src/ alone does not build. `harness/out/` stays out (generated, and in .dockerignore);
# `harness/fixtures/` is 630 KB across the two modules and has to be in the context.
COPY engine/harness/fixtures engine/harness/fixtures
COPY runner/harness/fixtures runner/harness/fixtures

# `-pl runner -am` builds the engine first and the runner against it, from the reactor. This is the line
# the build context exists for.
#
# TESTS RUN IN THE IMAGE BUILD, both modules'. The runner's suite is the ported specification of the one
# distinction the whole pipeline rests on — did the test RUN and fail, or did it never run — and an image
# that shipped without running it is an image nobody checked. Use `--build-arg SKIP_TESTS=-DskipTests`
# only to reproduce a build whose failure you are already looking at.
#
# AND THE RUNTIME CLASSPATH IS COLLECTED HERE, in the same invocation, which is not optional. THIS MODULE
# DOES NOT BUILD A FAT JAR — no shade, no spring-boot repackage — so fsm-runner.jar holds
# tech.mikhailov.fsm.runner.* and nothing else. `java -jar fsm-runner.jar` dies immediately with
#
#   Exception in thread "main" java.lang.NoClassDefFoundError: tech/mikhailov/fsm/lib/Json$JsonException
#
# because Json, Js.string and JsText.isSpace — the JavaScript semantics this port is specified in — live
# in the engine jar. `copy-dependencies` has to share this invocation: `engine` is resolved FROM THE
# REACTOR, so a separate `mvn -pl runner dependency:copy-dependencies` cannot find it (nothing ran
# `install`) and fails on the one artifact that matters. Collecting the tree rather than naming the jar
# also means a runtime dependency added to the pom lands in the image instead of turning into this same
# NoClassDefFoundError on the first container start.
ARG SKIP_TESTS=
RUN mvn -B -q -pl runner -am package ${SKIP_TESTS} \
      dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/libs

# ---- runtime -------------------------------------------------------------------------------------
# THE WHOLE TOOLCHAIN LIVES HERE, which is where this image parts company with engine/ and
# orchestrator/. Those two ship a JDK and a jar because they only ever run their own code. This one
# spawns `git`, `mvn` and `./gradlew` as child processes (Proc.execFile), so stripping Maven or the other
# JDKs out of the runtime stage would produce an image that starts, answers /health, and then reports
# every marker in the backlog as an infrastructure failure.
FROM debian:bookworm-slim
ARG JDK_VERSION
ARG MAVEN_VERSION
ARG TARGETARCH

# git — every prove starts with a clone. procps, tar, gzip, unzip, xz-utils — java-runner installed
# these and they stay: build scripts in the repositories under test shell out to them, and `ps`/`kill`
# are how a Maven that is wedged inside its twenty-minute ceiling gets looked at from `docker exec`.
# curl is the HEALTHCHECK's.
RUN apt-get update && apt-get install -y --no-install-recommends \
      curl ca-certificates git procps tar gzip unzip xz-utils \
    && rm -rf /var/lib/apt/lists/*

# --- JDKs 8/11/17/21/25 side by side at /opt/jdk/<major> -------------------------------------------
# Build.JDKS is this list; Build.javaHome is this path. The runner picks one per build from the request
# and RE-RUNS with a different one when a build reports a release-version mismatch, so every major it may
# switch to has to be present. WebGoat needs 25. GET /health answers with the majors it can actually see
# under /opt/jdk, so a download that failed shows up as a missing JDK rather than as a runner that
# accepts `"jdk": "25"` and then cannot compile with it.
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

# --- Maven ----------------------------------------------------------------------------------------
RUN set -eux; \
    curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
      -o /tmp/mvn.tar.gz; \
    tar -xzf /tmp/mvn.tar.gz -C /opt; \
    rm -f /tmp/mvn.tar.gz; \
    ln -s /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn

# JAVA_HOME is 25 here where java-runner's was 21, and the difference is not cosmetic: this jar is
# compiled with `release 25` and a 21 launcher refuses it with an UnsupportedClassVersionError on start.
# It does not leak into a build — Build.buildCmd sets JAVA_HOME and prepends /opt/jdk/<major>/bin to PATH
# for every child, from the request's `jdk` (default 17) — so what this pair really governs is the jar
# below and a human's `docker exec fsm-runner mvn -v`.
ENV JAVA_HOME=/opt/jdk/${JDK_VERSION}
ENV PATH=/opt/jdk/${JDK_VERSION}/bin:/opt/apache-maven-${MAVEN_VERSION}/bin:/usr/local/bin:$PATH

# THE RUN-TIME MIRROR, UNCONDITIONAL, and here java-runner is right and the engine's opt-in arg would be
# wrong. This resolves at RUN time, inside a container that IS on the mvn-cache network, against the
# Nexus that lives there. settings.xml sets mirrorOf=*, so it is not a cache in front of Central — it is
# the ONLY repository Maven will talk to, and every build of every target repository fails if `nexus` is
# unreachable. (Nexus is shared host infrastructure, not part of this project.) That is why the compose
# service MUST be on mvn-cache and why the orchestrator's DeploymentTest pins it: off that network the
# failure is not "the runner is down", it is hundreds of lines of Maven complaining that it cannot
# resolve anything, which reads as a broken repository rather than as broken wiring.
#
# BOTH COPIES, as java-runner had them, and they are not redundant. Maven merges the global settings
# (`conf/settings.xml`, under its own installation) with the user's (`~/.m2/settings.xml`), so the mirror
# is configured twice with the same value — harmless. The GLOBAL copy is the one that guarantees it: it
# applies whatever user a build runs as, and it survives somebody bind-mounting a private ~/.m2 over the
# user's, which would otherwise leave every build with no mirror and no error until nothing resolves. The
# user copy is where Maven's own documentation sends an operator looking, so it stays too.
COPY runner/settings.xml /opt/apache-maven-${MAVEN_VERSION}/conf/settings.xml

# Unprivileged, and it OWNS ITS CACHE — the same shape as the orchestrator's /state and for a sharper
# reason: this service runs arbitrary build scripts out of arbitrary repositories, and java-runner ran
# them as root. The uid is fixed so a named volume keeps working across image rebuilds; Docker seeds an
# EMPTY named volume from the image's directory, ownership included, which is what makes the fresh
# `fsm-runner-cache` volume writable on first start (see README.md, "The /cache volume" — an adopted
# root-owned volume from java-runner would fail every clone, which is the second reason not to share it).
# HOME is set explicitly rather than left to Docker's passwd lookup, because `mvn` reads $HOME to find
# ~/.m2 and `./gradlew` reads it to find ~/.gradle; unset, both would land in / and fail as root-only.
# The user's copy of settings.xml is made HERE rather than with a second COPY, so the ownership of
# /home/runner/.m2 is stated rather than inherited from however the builder chose to create it.
RUN useradd --system --create-home --uid 10003 runner \
    && mkdir -p /cache /home/runner/.m2 \
    && cp /opt/apache-maven-${MAVEN_VERSION}/conf/settings.xml /home/runner/.m2/settings.xml \
    && chown -R runner:runner /cache /home/runner
USER runner
ENV HOME=/home/runner

# The thin jar and its one dependency. See the `package` step: this module builds no fat jar, so both
# halves have to arrive or the process cannot start.
COPY --from=build /src/runner/target/fsm-runner.jar /app/fsm-runner.jar
COPY --from=build /src/runner/target/libs /app/libs

# 8090, which is not a preference: it is the port the Node runner served, so the orchestrator's
# `http://fsm-runner:8090` — HttpRunnerClient.DEFAULT_BASE_URL, application.yml and the compose
# FSM_RUNNER_URL passthrough — kept it, and RunnerServer.DEFAULT_PORT is 8090 for the same reason.
# (8092 is the engine, 8085 the orchestrator, which now serves the dashboard that used to be on 8091.)
# Nothing is published to the host: the caller reaches it by service name on the compose network.
ENV PORT=8090
EXPOSE 8090

# WHERE THE CLONES GO, and the image names the path it has prepared so compose can mount a volume on it.
# Left unmounted this still works and is a data-loss bug of the slow kind: /cache lands on the
# container's writable layer, so every `docker compose up -d` throws away every checkout and the next
# run re-clones a couple of dozen repositories and loses every `target/` the second build of each one
# depends on. BIND is deliberately not set — Runner defaults it to 0.0.0.0, and loopback would make this
# service unreachable from every other container while looking perfectly healthy from inside its own.
ENV CACHE=/cache

# NO LANG / LC_ALL AS AN IMAGE ENV, and this omission is a decision rather than an oversight — the
# tempting one-liner here is `ENV LANG=C.UTF-8`, on the argument that debian-slim has no locale and a
# Maven log with a non-ASCII path could arrive mangled into the text Build greps for "Tests run:" and
# "BUILD SUCCESS".
#
# It is refused because a locale set HERE is inherited by every build of every repository under test, and
# for a JDK 8 or 11 build it changes `file.encoding` from ASCII to UTF-8 — which changes what those tests
# do. This service exists to answer whether a defect reproduces, and its answers have to be the ones
# java-runner gave (it ran with no locale, and the differential harness compared against that). A
# deployment detail must not quietly become a variable in the verdict.
#
# THE READING SIDE NEEDS NO LOCALE: Proc decodes a child's stdout and stderr as UTF-8 explicitly, and this
# JVM's own console encoding is set on the command line below.
#
# BUT THE WRITING SIDE DOES, AND THAT IS NEW. `java.nio.file.Path` is not characters — it is the bytes the
# kernel takes — and the JDK produces them with `sun.jnu.encoding`, which is derived from THIS PROCESS'S
# LOCALE. With none, it is ANSI_X3.4-1968 and no filename outside ASCII can be named from Java at all:
#
#   * a repository with `src/main/java/…/Café.java` — an edit aimed at it came back "file not found",
#     which reads as a model that quoted a path wrong rather than as a runner that cannot spell it;
#   * a `test_path` outside ASCII threw InvalidPathException straight out of /run_test, which the engine
#     records as an infra_error and RETRIES — forever, because the retry lands in the same JVM;
#   * /fs/read_file answered the dashboard "source unavailable" for a file in the checkout.
#
# `file.encoding` is NOT that property (UTF-8 on every platform since JDK 18, which is exactly why this
# was invisible), and `-Dsun.jnu.encoding=UTF-8` does NOT set it either: the JDK overwrites the command
# line with the platform's value — "Platform defined encodings cannot be overridden on the command line",
# jdk.internal.util.SystemProps. The locale is the only lever, and PathEncodingTest measures both of those
# claims in a child JVM rather than restating them.
#
# SO THE LOCALE IS SET ON THE EXEC LINE BELOW, for one process. `env LC_ALL=C.UTF-8 java …` puts it in
# THIS process's environment and in no image layer — and Proc.isLocale then removes LANG, LANGUAGE and
# every LC_* from every child it starts, so a build under test sees exactly the empty locale it saw
# before. What used to be guaranteed by an absent line is now guaranteed by code that a test pins
# (ProcTest.theRunnersOwnLocaleIsStrippedFromEveryChild), which is the stronger arrangement: it survives
# somebody adding an `environment:` entry in compose.

# THE JVM FLAGS ARE ON THE COMMAND LINE, NOT IN JAVA_TOOL_OPTIONS, and that is the one place this image
# must diverge from engine/ and orchestrator/. JAVA_TOOL_OPTIONS is inherited by every child JVM — and
# every child here is a Maven, plus each surefire JVM it forks, running somebody else's tests. They would
# all pick up this process's heap ceiling (and print "Picked up JAVA_TOOL_OPTIONS:" into the very log
# Build.summarize parses). Flags after `java` reach this JVM and stop there.
#
# 25%, where the other two services take 75%. This JVM shares its container with the builds it exists to
# run: a 90-minute prove is a Maven and its forked test JVMs, which need most of the memory. A runner
# heap allowed to grow to three quarters of the container would win that race and get the build OOM-killed
# — reported as an infrastructure failure against a marker that was fine.
#
# stdout/stderr.encoding because the container has no console encoding to inherit, and these lines are
# read by grepping them.
#
# No shell wrapper: this process must receive SIGTERM directly. Runner's shutdown hook stops the listener
# and interrupts the build thread, which kills the child Maven instead of orphaning one that owns the
# shared workspace. A shell would swallow the signal and compose would SIGKILL the JVM ten seconds later.
# GET, which is what a HEALTHCHECK can issue without a body, and what RunnerServer answers on /health
# (POST /health is a 404 — the JS routed GET first and returned, and that is preserved).
#
# IT STAYS HONEST DURING A PROVE, which is the property that makes a healthcheck safe on this service at
# all: /run_test blocks for up to 90 minutes, and RunnerServer hands requests to a virtual-thread
# executor precisely so /health, /lease and the dashboard's source reads do not queue behind it. With
# com.sun.net.httpserver's default executor a busy runner would be indistinguishable from a dead one.
# `restart: unless-stopped` reacts to the process exiting, not to this probe, so an unhealthy report
# never kills a build by itself.
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${PORT}/health" >/dev/null || exit 1

# `env LC_ALL=C.UTF-8` AND THEN `java`, which is the whole of the encoding fix and is three things at
# once:
#
#   * it is the ONLY way to set sun.jnu.encoding (see the block above), and it reaches this process
#     because a variable placed before a command is that command's environment and nothing else's;
#   * it is NOT an image ENV, so no `docker exec` and no compose `environment:` carries it, and the
#     builds under test are unaffected — Proc strips it from every child regardless;
#   * `env` EXECS the command rather than forking it, so the JVM keeps pid 1 and receives compose's
#     SIGTERM directly. That is the same requirement the "no shell wrapper" note above states, and it is
#     why this is `env` and not `sh -c`.
#
# C.UTF-8 rather than a generated en_US.UTF-8: it is built into glibc, needs no locales package in a slim
# image, and carries no collation or formatting opinions — only the codeset, which is the part that
# matters here. If it were ever missing, glibc falls back to C and Runner's banner says so on the first
# line of the log (`sun.jnu.encoding=…` plus a WARNING), instead of the failure surfacing months later as
# a marker that came back "file not found".
#
# `-cp` AND A MAIN CLASS, NOT `-jar`, because this is a thin jar — see the `package` step in the build
# stage for what `java -jar fsm-runner.jar` does instead. The `*` is expanded by the JVM's own class-path
# handling and not by a shell, so it works in this exec form; a directory rather than a named jar so that a
# runtime dependency added to the pom is carried by the image rather than discovered on first start.
ENTRYPOINT ["env", "LC_ALL=C.UTF-8", \
            "java", \
            "-XX:MaxRAMPercentage=25", \
            "-Dstdout.encoding=UTF-8", \
            "-Dstderr.encoding=UTF-8", \
            "-cp", "/app/fsm-runner.jar:/app/libs/*", \
            "tech.mikhailov.fsm.runner.Runner"]
