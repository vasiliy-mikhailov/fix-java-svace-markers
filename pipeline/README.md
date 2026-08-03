# pipeline — the Maven reactor

**Start at the [repository README](../README.md).** It has the quickstart, the configuration table and
the operational notes. This file is the map of what is in this directory.

Moving this directory, or renaming the Compose project, is safe in the one way that matters: every
volume in `deploy/docker-compose.yml` pins its PHYSICAL name, so the live data is not addressed through
the directory or the project name. Keep those pins — without them Compose derives `<project>_<key>`,
looks for a volume that does not exist, creates it empty, and serves an empty backlog with nothing red.
A move still invalidates runbooks, and a deployed checkout has to be moved rather than re-cloned —
so back up the H2 volume cold first, carry `.env` across by hand (it is gitignored, so nothing moves
it for you), and afterwards confirm the marker count is what it was before you started.

## The reactor

`pom.xml` aggregates three modules. Build them together: `mvn -B test`.

| module | what it is | depends on |
|---|---|---|
| **engine** | The judgement, as pure functions over maps. Ten node classes, no I/O, ~900 tests. Also runs standalone over HTTP so one stage can be replayed by hand. | nothing |
| **orchestrator** | Spring Batch drives each marker through the chain. Owns H2, the dashboard, the REST API and the WebSocket. **Embeds `engine` as a library** — no HTTP hop between the queue and the judgement. | engine |
| **runner** | Clones the target repo, writes the test, applies the patch, runs Maven twice. Its parsing and edit rules are pinned by a 23,401-case frozen differential harness (`runner/harness/README.md`). **A library, not a service** — `LocalRunner` is what `orchestrator` calls; `RunnerServer` wraps the same code over HTTP for a deployment that wants the build sandbox split off. | engine |

Each module has its own README with the detail — endpoints, configuration, and why particular decisions
were made the way they were.

`runner` stays a module rather than being folded into `orchestrator`, and that is deliberate: its 216
tests are the specification of the one distinction the whole pipeline rests on — did the test RUN and
fail, or did it never run — and it holds a zero-third-party-dependency policy that a merge into a Spring
Boot module would quietly break.

## Deployment

`deploy/docker-compose.yml` is the whole deployment: **one service**, plus `engine` behind a profile for
replaying a single stage by hand. `DeploymentTest` pins that, so a second running service is a red test
rather than a discovery. `deploy/.env.example` documents every variable it reads.

`Dockerfile` is at the **reactor root**, which is also its build context, because `orchestrator` resolves
`tech.mikhailov.fsm:engine` and `tech.mikhailov.fsm:runner` from the reactor and nothing publishes those
jars. Its runtime stage carries git, JDK 8/11/17/21/25 and Maven, because the process it starts spawns
all three over the repositories under analysis.

```bash
docker compose up -d --build                            # one image, resolving from Central

DOCKER_BUILDKIT=0 docker build --network mvn-cache \
  --build-arg MAVEN_MIRROR_URL=http://nexus:8081/repository/maven-public/ \
  -t fsm:latest .
```

The second form is not a stylistic preference. `docker compose build` cannot join a network and BuildKit
rejects custom network modes, so a Maven mirror that only resolves on `mvn-cache` needs the legacy
builder. Without it the build fails minutes in with `nexus: No address associated with hostname`.

That build argument governs the image's OWN build. Which repository the analysed projects resolve from
is the `MAVEN_MIRROR_URL` **environment variable** on the running container — empty means Central, and
it takes effect with no rebuild. See `runner/src/main/java/.../MavenSettings.java`.

## Tests

```bash
mvn -B test                        # 1751 tests across the three modules
orchestrator/playwright/run.sh     # the browser suite, inside the Playwright image
```

The browser tests are excluded from `mvn test` deliberately — they need the browsers that ship in that
image. The build prints one line saying so and giving the command, and a test asserts that line still
matches reality, so the notice cannot quietly become a lie.
