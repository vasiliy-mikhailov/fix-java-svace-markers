# The prove, in one image. See SPEC.md.
#
# JDK 25 BECAUSE THE SUBJECT NEEDS IT, not because the agent does. The agent targets 21; WebGoat
# declares <java.version>25</java.version> and the RED and GREEN builds run inside this container
# against the subject's own pom. A JDK the subject cannot compile under turns every marker into
# "no test executed", which is the one failure that looks like a verdict.
#
# Maven and git are runtime dependencies for the same reason: the runner shells out to `mvn` in the
# checkout, and a prove starts by cloning.

# ---------------------------------------------------------------- builder
FROM maven:3.9-eclipse-temurin-25 AS builder

# langchain4j-deepagents IS NOT ON MAVEN CENTRAL. Building it here rather than vendoring a jar keeps
# the provenance visible: the commit that produced the artifact is in the log of this build.
ARG DEEPAGENTS_REPO=https://github.com/udayogra/langchain4j-deepagents.git
ARG DEEPAGENTS_REF=main
RUN git clone --depth 1 --branch ${DEEPAGENTS_REF} ${DEEPAGENTS_REPO} /src/deepagents \
    # THE TOOL CEILING IS A LITERAL IN SubAgentRuntime, not a setting: `bipush 25`. Twenty-five
    # sequential calls is not many for an agent reading a class, its callers and its tests before
    # writing anything, and hitting it is a thrown RuntimeException that ends the prove — a marker
    # lost to a budget rather than to anything about the marker.
    #
    # Patched here rather than worked around in our code, because the alternative is to stop using
    # SubAgentRuntime and rebuild the tool loop, which is the thing this program exists not to do.
    # The sed is in the build so it is visible in the build; if upstream makes it a parameter this
    # line stops matching and the build fails loudly rather than silently reverting.
    && sed -i 's/\.maxSequentialToolsInvocations(25)/.maxSequentialToolsInvocations(Integer.MAX_VALUE)/' \
        /src/deepagents/src/main/java/com/deepagents/langchain4j/subagents/SubAgentRuntime.java \
    && grep -q 'maxSequentialToolsInvocations(Integer.MAX_VALUE)' \
        /src/deepagents/src/main/java/com/deepagents/langchain4j/subagents/SubAgentRuntime.java \
    && mvn -q -f /src/deepagents -DskipTests install

WORKDIR /src/agent
# BUILT FROM THE REPOSITORY ROOT, not from agent/. The spec is part of the project and has to reach
# the image, and Docker will not COPY above its context — so the context is the project and these
# paths carry the agent/ prefix. Build with `docker build -f agent/Dockerfile .` from the root.
# The pom alone first, so a source-only change does not re-resolve every dependency.
COPY agent/pom.xml .
RUN mvn -q -B dependency:go-offline

COPY agent/src ./src
RUN mvn -q -B -DskipTests package \
    && mvn -q -B dependency:copy-dependencies -DoutputDirectory=target/lib

# ---------------------------------------------------------------- the other JDKs
#
# COPIED FROM THE OFFICIAL IMAGES, NOT INSTALLED. apt would pull whatever Debian happens to package,
# which is not every version and not the same build; these are the Temurin releases themselves, and
# the copy is reproducible and needs no package index at build time.
#
# WHY AT ALL: javac 25 compiles for --release 8 through 25, so nothing here is about compiling. It is
# about what the SUBJECT'S TESTS RUN ON. Surefire forks a JVM from JAVA_HOME, so a Java 8 project's
# tests execute on 25 unless there is another JDK to point at — and on 25 they meet strong
# encapsulation, removed APIs and bytecode libraries that cannot read class file 69. Every one of
# those arrives here as "the build produced no test result", which this program refuses to treat as
# evidence and which costs the marker anyway.
#
# The cost is about a gigabyte of image. That is the price of being able to prove a marker in a
# project written before 2018.
FROM eclipse-temurin:8-jdk   AS jdk8
FROM eclipse-temurin:11-jdk  AS jdk11
FROM eclipse-temurin:17-jdk  AS jdk17
FROM eclipse-temurin:21-jdk  AS jdk21

# ---------------------------------------------------------------- runtime
FROM maven:3.9-eclipse-temurin-25

RUN apt-get update \
    && apt-get upgrade -y --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

COPY --from=jdk8  /opt/java/openjdk /opt/java/8
COPY --from=jdk11 /opt/java/openjdk /opt/java/11
COPY --from=jdk17 /opt/java/openjdk /opt/java/17
COPY --from=jdk21 /opt/java/openjdk /opt/java/21

# Not root: a prove clones a stranger's repository and runs its build. That build is arbitrary code,
# and it should not be arbitrary code as uid 0.
RUN useradd --create-home --uid 10001 prover
WORKDIR /work

COPY --from=builder /src/agent/target/agent-*.jar      /opt/agent/agent.jar
COPY --from=builder /src/agent/target/lib              /opt/agent/lib
COPY agent/entrypoint.sh                               /opt/agent/entrypoint.sh
# THE SPEC TRAVELS WITH THE CODE. entrypoint.sh copies it into the results volume at start-up,
# because that is the only tree the agents' file tools can reach — see the note there.
COPY spec                                              /opt/agent/spec
RUN sed -i 's/\r$//' /opt/agent/entrypoint.sh \
    && chmod +x /opt/agent/entrypoint.sh && chown -R prover:prover /work

# EVERY VOLUME PATH IS MADE, OWNED, BEFORE IT IS DECLARED. Docker takes a fresh named volume's
# ownership from the image directory underneath it — and creates it root-owned when there is nothing
# there. This image does not run as root, so a volume declared on a missing path is a volume its own
# process cannot write: the trace silently fails, or Maven cannot cache and re-resolves every build.
RUN mkdir -p /results /work/checkouts /home/prover/.m2 \
    && chown -R prover:prover /results /work /home/prover/.m2

USER prover
# THE RUNNING BUILD, NAMEABLE. The manifest a shell fetches reports this, so a portal can say
# exactly which build it is talking to and two deployments cannot be mistaken for each other.
# Passed at build time: `docker build --build-arg FSM_VERSION=$(git rev-parse --short HEAD)`.
ARG FSM_VERSION=unknown
ENV FSM_VERSION=$FSM_VERSION \
    RESULTS=/results \
    CHECKOUTS=/work/checkouts

# The trace and the settlements are the whole record; a run that keeps neither has proved nothing
# anybody can read. Mount this.
#
# AND MOUNT THE MAVEN CACHE. It lives in the container filesystem otherwise, so every recreation
# starts from an empty local repository and the next build re-downloads WebGoat's whole dependency
# tree — minutes per marker, paid again on every redeploy, and invisible in the trace because the
# build simply takes longer.
VOLUME ["/results", "/home/prover/.m2"]

EXPOSE 8087
ENTRYPOINT ["/opt/agent/entrypoint.sh"]
CMD ["dashboard"]
