# Self-contained build/test runner. Everything it needs is fetched here from public sources.
#
# Node, not Python: the rest of the fleet is Node and the runner was the last Python service. The
# JavaParser navigation server is gone too — it existed to answer goto-definition for the LLM
# suspector's tools, and nothing has queried it since Svace markers replaced that stage.
#
# This deliberately does NOT start from a base image built elsewhere on the host (the parent project's
# `bjv-alljdk`), and does not expect any jar to be staged into the build context by hand. Both of those
# made the image unbuildable on a clean checkout: `docker compose build` failed with "pull access
# denied" for a local-only base, and with "/nav/lib not found" for jars that live in another repo.
# This file builds from `debian:bookworm-slim` and downloads what it needs, so a fresh clone builds.
FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
      curl ca-certificates git procps tar gzip unzip xz-utils \
    && rm -rf /var/lib/apt/lists/*

# Node 24 from upstream, matching the dashboard. Debian bookworm ships 18, and pinning the fleet to a
# single major keeps "works on the dashboard" from meaning something different here.
ENV NODE_VERSION=24.9.0
RUN set -eux; \
    curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.xz" -o /tmp/node.tar.xz; \
    tar -xJf /tmp/node.tar.xz -C /opt; \
    rm -f /tmp/node.tar.xz; \
    ln -s /opt/node-v${NODE_VERSION}-linux-x64/bin/node /usr/local/bin/node; \
    node --version

# --- JDKs 8/11/17/21/25 side by side at /opt/jdk/<major> -------------------------------------------
# The runner picks one per build and RE-RUNS with a different one when javac reports a release-version
# mismatch, so every major it may switch to has to be present. WebGoat needs 25.
RUN set -eux; \
    for v in 8 11 17 21 25; do \
      mkdir -p /opt/jdk/$v; \
      curl -fsSL "https://api.adoptium.net/v3/binary/latest/${v}/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk" \
        -o /tmp/jdk.tar.gz; \
      tar -xzf /tmp/jdk.tar.gz -C /opt/jdk/$v --strip-components=1; \
      rm -f /tmp/jdk.tar.gz; \
      /opt/jdk/$v/bin/java -version; \
    done

# --- Maven ----------------------------------------------------------------------------------------
# Gradle is deliberately absent: every Gradle project the runner builds is invoked through its own
# ./gradlew wrapper, which downloads the version that project pins.
ENV MAVEN_VERSION=3.9.9
RUN set -eux; \
    curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
      -o /tmp/mvn.tar.gz; \
    tar -xzf /tmp/mvn.tar.gz -C /opt; \
    rm -f /tmp/mvn.tar.gz; \
    ln -s /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn

ENV JAVA_HOME=/opt/jdk/21
ENV PATH=/opt/jdk/21/bin:/opt/apache-maven-3.9.9/bin:$PATH

# Maven resolves through the Nexus mirror on the mvn-cache network. settings.xml sets mirrorOf=*, so
# this is not a cache in front of Central — it is the ONLY repository Maven will talk to, and every
# build fails if the `nexus` container is down. (Nexus is shared host infrastructure, not part of the
# parent project.) Nothing else in this image needs it: the JDKs and Maven come from upstream, and the
# runner itself has no npm dependencies.
COPY settings.xml /root/.m2/settings.xml
COPY settings.xml /opt/apache-maven-3.9.9/conf/settings.xml

COPY src /app/src
COPY lib /app/lib

ENV PORT=8090
WORKDIR /app
ENTRYPOINT []
CMD ["node", "/app/src/server.js"]
