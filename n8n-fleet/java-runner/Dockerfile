# Self-contained build/test runner. Everything it needs is fetched here from public sources.
#
# This deliberately does NOT start from a base image built elsewhere on the host (the parent project's
# `bjv-alljdk`), and does not expect any jar to be staged into the build context by hand. Both of those
# made the image unbuildable on a clean checkout: `docker compose build` failed with "pull access
# denied" for a local-only base, and with "/nav/lib not found" for jars that live in another repo.
# This file builds from `debian:bookworm-slim` and downloads what it needs, so a fresh clone builds.
FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
      curl ca-certificates git python3 procps tar gzip unzip \
    && rm -rf /var/lib/apt/lists/*

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
# parent project.) The nav jars above are fetched straight from Central at build time instead, so the
# image itself builds without it.
COPY settings.xml /root/.m2/settings.xml
COPY settings.xml /opt/apache-maven-3.9.9/conf/settings.xml

# --- Code navigation (JavaParser SymbolSolver) ----------------------------------------------------
# Fetched from Maven Central at build time rather than pre-staged into the build context.
RUN set -eux; \
    mkdir -p /opt/nav/lib; \
    cd /opt/nav/lib; \
    for u in \
      com/github/javaparser/javaparser-core/3.26.2/javaparser-core-3.26.2.jar \
      com/github/javaparser/javaparser-symbol-solver-core/3.26.2/javaparser-symbol-solver-core-3.26.2.jar \
      com/google/guava/guava/33.3.0-jre/guava-33.3.0-jre.jar \
      com/google/guava/failureaccess/1.0.2/failureaccess-1.0.2.jar \
      com/google/guava/listenablefuture/9999.0-empty-to-avoid-conflict-with-guava/listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar \
      org/javassist/javassist/3.30.2-GA/javassist-3.30.2-GA.jar \
      org/checkerframework/checker-qual/3.43.0/checker-qual-3.43.0.jar \
      com/google/errorprone/error_prone_annotations/2.28.0/error_prone_annotations-2.28.0.jar \
      com/google/j2objc/j2objc-annotations/3.0.0/j2objc-annotations-3.0.0.jar \
      com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar \
    ; do curl -fsSLO "https://repo1.maven.org/maven2/$u"; done

COPY NavServer.java /opt/nav/NavServer.java
RUN /opt/jdk/21/bin/javac -cp "/opt/nav/lib/*" -d /opt/nav/classes /opt/nav/NavServer.java

COPY runner.py /runner.py

ENV PORT=8090
WORKDIR /
ENTRYPOINT []
CMD ["python3", "/runner.py"]
