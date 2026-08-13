# 17 — Mounting this tool in a shell

This tool is one of several — `bump-java-version`, `improve-java-tests` and whatever follows — that a
developer is meant to reach from **one** UI. Each lives in its own repository, ships its own container
and deploys on its own cadence. A shell composes them.

**The shell is not in this repository and will be written separately.** This chapter is the contract
it implements against. Everything here is a promise this repository keeps; anything not written down
here is not a promise, and a shell that relies on it will break on a deploy nobody thought was
breaking.

---

## The shape: a zone behind a rewrite

The mechanism is Next.js Multi-Zones, chosen for the same reasons the portal design chose it over
Module Federation and import maps: every zone is a complete application, so App Router and RSC work
inside it without compromise, versions need not match across zones, and a zone that falls over does
not take the shell with it. The cost — a full navigation when crossing a zone boundary, and a
duplicated framework runtime — is acceptable for tools a developer moves between a few times an hour.

```
                     ┌──────────────────────────────────────────┐
  developer ────────▶│  shell            (separate repository)  │
                     │  nav, identity, degraded pages           │
                     └───────┬──────────────────┬───────────────┘
                    rewrites │                  │
            /fix-java-svace-markers/*    /bump-java-version/*
                             ▼                  ▼
                     ┌───────────────┐  ┌───────────────┐
                     │ THIS TOOL     │  │ another tool  │
                     │ zone + JSON   │  │               │
                     └───────────────┘  └───────────────┘
```

The shell owns the public origin and rewrites a path prefix to this tool. This tool never assumes it
owns `/`.

## What this tool promises

| | value |
|---|---|
| **id** | `fix-java-svace-markers` — stable, and the key a shell stores against |
| **base path** | `/fix-java-svace-markers` by default, overridable with `BASE_PATH` |
| **asset prefix** | `<base path>-static`, so two zones' `_next/static` cannot collide |
| **manifest** | `GET <base>/.well-known/microfrontend.json` |
| **health** | `GET <base>/api/health` |
| **API root** | `<base>/api` |

**The base path is configuration, not a constant.** A shell that wants this tool at
`/tools/markers` sets `BASE_PATH=/tools/markers` and every link and asset URL this tool emits follows.
A zone with a hard-coded prefix can only ever be mounted one way, and the second tool to want the same
prefix cannot be mounted at all.

### The manifest

The one document a shell needs, served by the tool so it describes the version actually running
rather than what someone wrote down once:

```json
{
  "id": "fix-java-svace-markers",
  "name": "Prove markers",
  "description": "Proves static-analysis markers: a failing test, a patch, the same test passing.",
  "version": "<git sha of the running image>",
  "basePath": "/fix-java-svace-markers",
  "assetPrefix": "/fix-java-svace-markers-static",
  "api": "/fix-java-svace-markers/api",
  "health": "/fix-java-svace-markers/api/health",
  "nav": [
    { "label": "Markers",    "path": "/",          "badge": null },
    { "label": "Findings",   "path": "/overwatch", "badge": "findings" },
    { "label": "Ask",        "path": "/chat",      "badge": null },
    { "label": "Settings",   "path": "/settings",  "badge": null }
  ],
  "badges": { "findings": { "endpoint": "/api/badges", "field": "findings" } }
}
```

`nav` paths are **relative to the base path**. The shell prefixes them; this tool does not know what
the shell's URL bar says.

`badges` is how a count reaches the shell's navigation without the shell knowing what a finding is.
It polls `endpoint` and reads `field`. That indirection is the point: when `bump-java-version` wants a
badge for something else entirely, the shell needs no change.

### Health, and what degraded means

`GET <base>/api/health` returns `200` with `{"ok": true, "version": "<sha>"}` when the tool can serve
its record, and `503` with `{"ok": false, "why": "<one sentence>"}` when it cannot.

**It reports on the record, not on the model endpoint.** The dashboard is worth serving when the
inference endpoint is unreachable — the whole record is still readable and that is most of what
anybody comes for. A health check that went red because a model was down would have a shell hiding a
tool that was working.

## What this tool does NOT do, and the shell must

**It does not authenticate anybody.** There is no session, no CSRF token and no origin check. Today a
reverse proxy in front of the container provides basic auth (chapter 15).

This is the part a shell author has to read twice, because mounting this tool wrong publishes a
credential: **`/settings` renders the model API key and the git token into the page**, deliberately,
because the reveal and copy buttons cannot work otherwise. That trade is defensible for one person
behind their own proxy. It is not defensible on a portal several developers reach.

So the contract is:

- the shell authenticates, and only reaches this tool for a request it has already authorised;
- the tool's port is **never** published directly — the shell's origin is the only way in;
- if the portal has more than one class of user, the shell must gate `<base>/settings` and
  `<base>/api/settings` itself. This tool cannot do it: it has no idea who is asking.

A tool that had its own login would be four logins by the fourth tool, which is the thing a portal
exists to avoid. So identity stays in the shell — but then the shell owns the consequence.

## The theme, which does not cross a zone boundary by itself

The portal's palette is the portal's: this tool copies all 53 of its tokens verbatim into
`packages/ui/src/tokens.css`, in both themes, switched by `.dark` on the root — the way the portal
switches them. A zone with its own palette is what makes a portal of microfrontends feel like four
different websites behind one nav bar.

But **a zone renders its own document.** Multi-Zones means a full navigation across a boundary, so
the shell cannot put a class on this tool's `<html>`. Whatever the shell does to its own root element
is invisible here.

The shell and this tool are on **one public origin** — that is what the rewrite buys — so a cookie is
shared. The contract:

- the shell writes `theme=dark` or `theme=light` as a cookie on `/`, readable by every zone;
- this tool reads it on the way out and puts `.dark` on its root to match;
- absent or unrecognised means follow `prefers-color-scheme`, which is also what this tool does when
  nobody has mounted it at all.

`localStorage` would work for the same reason and is the wrong choice: it is not readable while the
document is being built, so the page would paint in one theme and correct itself in the other. A
cookie arrives with the request.

## Versioning

The manifest's `version` is the git SHA of the running image, which is also its Docker tag
(`vasiliymikhailov/fsm-agent:<sha>`). A shell can therefore report exactly which build it is talking
to, and two shells pointed at different deployments cannot be confused for each other.

**Additive changes only, to the manifest and the API.** A shell is deployed on its own cadence and
will be older than this tool as often as not. Adding a field is safe; renaming or removing one breaks
a shell nobody redeployed. If a breaking change is unavoidable, serve both shapes until the shell has
moved.

## Why the frontend is here and the shared components are not

Each tool keeps its own frontend in its own repository. The parts these tools will obviously
share — a table of work items, the chat with the supervisor, the full record of what an agent did —
are **not** extracted yet, on purpose: there is one tool. Two examples of a thing are the earliest
point at which the difference between them is a fact rather than a guess, and a shared package
designed from one example is a package the second tool has to fight.

When `bump-java-version` exists, the overlap will be visible and can be lifted into a published
package then. What this chapter fixes now is the part that is expensive to change later: **the mount
contract**. Components can be extracted at leisure; a base path baked into a hundred links cannot.
