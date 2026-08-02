# pipeline — the Maven reactor

**Start at the [repository README](../README.md).** It has the quickstart, the configuration table and
the operational notes. This file is the map of what is in this directory.

This pipeline ran on n8n until July 2026; nothing here runs n8n now, and no Node, Python or n8n service
is left. This directory was `n8n-fleet/` and `deploy/` was `n8n/` until that name was retired too.

Moving the compose file is safe in the one way that matters — every volume in
`deploy/docker-compose.yml` pins its PHYSICAL name, so the live data is not addressed through the
directory or the project name — but it does invalidate runbooks, and the deployed checkout has to be
moved rather than re-cloned. `migrate-to-spring.sh` does that properly, with backups and a post-flight
that verifies the marker count survived.

## The reactor

`pom.xml` aggregates three modules. Build them together: `mvn -B test`.

| module | what it is | depends on |
|---|---|---|
| **engine** | The judgement, as pure functions over maps. Ten node classes, no I/O, ~900 tests. Also runs standalone over HTTP so one stage can be replayed by hand. | nothing |
| **orchestrator** | Spring Batch drives each marker through the chain. Owns H2, the dashboard, the REST API and the WebSocket. **Embeds `engine` as a library** — no HTTP hop between the queue and the judgement. | engine |
| **runner** | Clones the target repo, writes the test, applies the patch, runs Maven twice. Ships JDK 8/11/17/21/25 + Maven. Ported from a Node service, with a 23,401-case differential harness proving the two agree. | engine |

Each module has its own README with the detail — endpoints, configuration, and why particular decisions
were made the way they were.

## Deployment

`deploy/docker-compose.yml` is the whole deployment: three services. Two tests pin that list, so a fourth
service is a red test rather than a discovery. `deploy/.env.example` documents every variable it reads.

Images build from the **reactor root**, not from a module directory, because `orchestrator` and `runner`
resolve `tech.mikhailov.fsm:engine` from the reactor and nothing publishes that jar:

```bash
docker compose build                                    # all three, resolving from Central

DOCKER_BUILDKIT=0 docker build --network mvn-cache \
  --build-arg MAVEN_MIRROR_URL=http://nexus:8081/repository/maven-public/ \
  -f orchestrator/Dockerfile -t fsm-orchestrator:latest .
```

The second form is not a stylistic preference. `docker compose build` cannot join a network and BuildKit
rejects custom network modes, so a Maven mirror that only resolves on `mvn-cache` needs the legacy
builder. Without it the build fails minutes in with `nexus: No address associated with hostname`.

## Tests

```bash
mvn -B test                        # 1727 tests across the three modules
orchestrator/playwright/run.sh     # the browser suite, inside the Playwright image
```

The browser tests are excluded from `mvn test` deliberately — they need the browsers that ship in that
image. The build prints one line saying so and giving the command, and a test asserts that line still
matches reality, so the notice cannot quietly become a lie.

## `tools/`

Helpers that are not part of the running system. Nothing on the build path, nothing deployed.
