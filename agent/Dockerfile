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
    && mvn -q -f /src/deepagents -DskipTests install

WORKDIR /src/agent
# The pom alone first, so a source-only change does not re-resolve every dependency.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B -DskipTests package \
    && mvn -q -B dependency:copy-dependencies -DoutputDirectory=target/lib

# ---------------------------------------------------------------- runtime
FROM maven:3.9-eclipse-temurin-25

# Not root: a prove clones a stranger's repository and runs its build. That build is arbitrary code,
# and it should not be arbitrary code as uid 0.
RUN useradd --create-home --uid 10001 prover
WORKDIR /work

COPY --from=builder /src/agent/target/agent-*.jar      /opt/agent/agent.jar
COPY --from=builder /src/agent/target/lib              /opt/agent/lib
COPY entrypoint.sh                                     /opt/agent/entrypoint.sh
RUN chmod +x /opt/agent/entrypoint.sh && chown -R prover:prover /work

USER prover
ENV RESULTS=/results \
    CHECKOUTS=/work/checkouts

# The trace and the settlements are the whole record; a run that keeps neither has proved nothing
# anybody can read. Mount this.
VOLUME ["/results"]

EXPOSE 8087
ENTRYPOINT ["/opt/agent/entrypoint.sh"]
CMD ["dashboard"]
