# fsm-orchestrator-ui — the dashboard's browser tests, and the browser they need.
#
# THE BUILD CONTEXT IS THE REACTOR ROOT (pipeline/), NOT THIS DIRECTORY, for the same reason
# ../Dockerfile says so: `orchestrator` depends on `tech.mikhailov.fsm:engine` and resolves it FROM THE
# REACTOR, because nothing publishes that jar anywhere.
#
#   cd pipeline
#   docker build -f orchestrator/playwright/Dockerfile -t fsm-orchestrator-ui:latest .
#   docker run --rm --shm-size=1g fsm-orchestrator-ui:latest
#
# ...or orchestrator/playwright/run.sh, which is those two lines with the Nexus passthrough attached.
#
# ---------------------------------------------------------------------------------------------------
# WHY A CONTAINER, AND WHY THIS BASE
# ---------------------------------------------------------------------------------------------------
# A browser is not a Maven dependency. It is a ~150 MB binary with a system-library contract (a dozen
# .so files whose absence produces a launch failure that reads as a broken test, not as a broken
# machine), and it has to match the driver in the jar EXACTLY — Playwright looks the browser up by
# revision, and a browser one release out from its driver fails in ways that look like the page is
# wrong. Pinning them by hand, per developer, is a per-machine decision to get wrong.
#
# So the browsers come from the image the Playwright project publishes for exactly this, and the image
# tag and the `playwright.version` property in orchestrator/pom.xml MOVE TOGETHER. They are the same
# number in two files and that is the one duplication here worth having: the alternative is a version
# skew nothing announces.
#
# Every tool in this fleet that is not Java already lives in a container. This is that rule, applied to
# a browser.
ARG PLAYWRIGHT_VERSION=1.49.0

# The official Playwright-for-Java image: JDK, browsers and their system libraries, all at matching
# versions. `-noble` is Ubuntu 24.04; `-jammy` (22.04) is published for the same versions if a host
# needs the older glibc.
FROM mcr.microsoft.com/playwright/java:v${PLAYWRIGHT_VERSION}-noble

ARG PLAYWRIGHT_VERSION
# ---------------------------------------------------------------------------------------------------
# THE ONE THING THE BASE IMAGE DOES NOT ALREADY HAVE: THIS PROJECT'S JDK
# ---------------------------------------------------------------------------------------------------
# The Playwright Java image ships a JDK, and it is not 25. Every module in this fleet targets 25
# (maven.compiler.release in the reactor pom, java.version here), so the module would not compile on
# the image's own — and "compile it against 17 for the tests" is not a smaller version of the same
# check: it is a different program under test.
#
# So Temurin 25 is added on top, from the same source and into the same /opt/jdk/<major> layout as
# ../Dockerfile and ../../engine/Dockerfile use, so "where is the JDK" has one answer everywhere in
# this repository. What is NOT replaced is the part that is hard: the browsers, their revisions and
# their system libraries all stay exactly as the image shipped them.
ARG JDK_VERSION=25
ARG MAVEN_VERSION=3.9.9
# Set by buildkit. Following it is what lets this image build on an arm64 laptop as well as on the
# amd64 host; hardcoding x64 builds fine anywhere and dies at RUN time with "exec format error".
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
    ln -sf /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn

ENV JAVA_HOME=/opt/jdk/${JDK_VERSION}
ENV PATH=/opt/jdk/${JDK_VERSION}/bin:/usr/local/bin:$PATH

# ---------------------------------------------------------------------------------------------------
# WHERE THE BROWSERS ARE, AND THE FACT THAT NOTHING DOWNLOADS THEM
# ---------------------------------------------------------------------------------------------------
# /ms-playwright is where the base image put them. Restated rather than inherited silently, because
# this is the line that decides whether the suite runs against the image's pinned browsers or fails
# with "Executable doesn't exist at ..." — and the second one is the failure that gets "fixed" by
# adding a browser download to a test, which is the per-machine decision this image exists to remove.
#
# SKIP_BROWSER_DOWNLOAD is belt and braces: nothing in this build asks Playwright to install anything,
# and if something ever does, it must fail loudly rather than quietly fetch a second copy at a revision
# nobody pinned.
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

# THE TWO CHROMIUM FLAGS A CONTAINER NEEDS, AND WHY THEY ARE HERE RATHER THAN IN THE TEST.
#
#   --no-sandbox            this image runs as root and Chromium refuses to start as root without it.
#                           It is a property of the sandbox the browser is in, not of the page; baking
#                           it into the Java would also turn it off for a developer running the suite
#                           on a laptop, where the sandbox is worth having. The browser here loads one
#                           origin — an application this same process started, on loopback.
#   --disable-dev-shm-usage Docker gives a container 64 MB of /dev/shm by default and Chromium's
#                           renderer fills it, then crashes mid-test. `docker run --shm-size=1g` fixes
#                           the same thing from outside and the run command asks for it too; this flag
#                           is what makes the image correct when somebody forgets.
ENV FSM_UI_CHROMIUM_ARGS="--no-sandbox --disable-dev-shm-usage"

# Mirror, OPT-IN, exactly as the other two images argue it: `docker build` does not join a compose
# network, so a baked-in http://nexus:8081 makes the image unbuildable everywhere including on the host
# that has Nexus. Default is Central; pass the mirror where it is reachable:
#
#   docker build --network mvn-cache -f orchestrator/playwright/Dockerfile \
#     --build-arg MAVEN_MIRROR_URL=http://nexus:8081/repository/maven-public/ -t fsm-orchestrator-ui .
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

# EVERY MODULE'S POM. Maven builds the model for every module the aggregator lists before `-pl` selects
# anything, so a missing one fails with "Child module /src/runner of /src/pom.xml does not exist" — a
# message about a module this image does not compile. Same rule as ../Dockerfile: a module added to
# ../../pom.xml is a line added here.
COPY pom.xml ./
COPY engine/pom.xml engine/pom.xml
COPY orchestrator/pom.xml orchestrator/pom.xml
COPY runner/pom.xml runner/pom.xml
RUN mvn -B -q -pl orchestrator -am -Pui dependency:go-offline || true

COPY engine/src engine/src
COPY orchestrator/src orchestrator/src
# The engine's frozen differential fixtures: a TEST INPUT, and `-am` builds the engine with its tests.
COPY engine/harness/fixtures engine/harness/fixtures

# ---------------------------------------------------------------------------------------------------
# BUILT AT IMAGE BUILD TIME, RUN AT `docker run` TIME
# ---------------------------------------------------------------------------------------------------
# The engine and the orchestrator are compiled and INSTALLED here, tests skipped, so that the container
# starts a browser rather than a compiler and so that a red UI test is a failed RUN — re-runnable, with
# `docker run ... -Dtest=ThePageIsNotBlankTest` available — rather than a failed image build you have
# to rebuild to look at again.
#
# `install` and not `package`, so the entrypoint below can be `-pl orchestrator` with no `-am`: the
# engine resolves from this image's own ~/.m2. Without that, every run would rebuild the engine AND run
# the engine's whole test suite before reaching the first browser test.
RUN mvn -B -pl orchestrator -am -DskipTests install

# WARM UP SUREFIRE'S PROVIDER. Its JUnit-platform provider is resolved when tests actually execute, not
# when they are skipped, so without this line the first `docker run` would go to the network for it.
# Selecting a class that does not exist runs nothing and resolves everything; `|| true` because this is
# an optimisation and must never be the reason an image fails to build.
RUN mvn -B -pl orchestrator -Pui test \
      -Dtest='NoSuchClassResolvesTheProvider' \
      -Dsurefire.failIfNoSpecifiedTests=false || true

# THE SUITE IS THE WHOLE POINT OF THE CONTAINER, so it is the entrypoint.
#
# `-Pui` inverts this module's surefire filter: every @Tag("ui") class and nothing else. The
# ordinary suite is the ordinary build's job — running it here would put six minutes of Spring context
# in front of the browser, and would let a green container run be quoted as "the module passes", which
# it would not be.
#
# Arguments passed to `docker run` are appended, so `-Dtest=…`, `-o` or `-X` work without a rebuild.
ENTRYPOINT ["mvn", "-B", "-pl", "orchestrator", "-Pui", "test"]
