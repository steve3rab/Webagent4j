# Architecture

WebAgent4J follows ports and adapters at module scale. Public contracts contain no Playwright types.
The core facade discovers optional browser providers with Java `ServiceLoader`; applications add the
provider at runtime. Domain modules depend toward stable contracts and the Playwright adapter depends
on those contracts.

```text
Application / CLI / future optional integration
                    |
                    v
                  Core
                    |
                    v
              Browser API
          /      |       |          \
        DOM    Locator  Action   Observation API
         |        |       |            ^
         +--> Locator API  +------> Verification
                           |            |
                           +-----> Observation Engine

Playwright adapter ------> Browser API and domain contracts
```

Plugins use a separate, opt-in composition path. The locator engine remains unaware of service
discovery:

```text
Application
    |
    v
PluginLoader -> ServiceLoader<ILocatorStrategyProvider>
    |
    v
validated PluginRegistry -> LocatorStrategyRegistry -> LocatorEngine
```

`webagent4j-plugin-api` depends on `webagent4j-locator`, never the reverse. No default engine,
browser startup, workflow, recording, crawler, or action path calls `PluginLoader`; without the
explicit application call, zero locator plugins are loaded. See [plugins.md](plugins.md).

`webagent4j-locator-api` breaks the dependency cycle required by scoped queries: it contains generic
fluent contracts and immutable definitions, `IElement` exposes `find()`, and the locator engine depends
on the DOM contract for scoring. It also carries `ILocatorScope<E>`, the typed contract for
`within(E)`/`within(ILocatorScope<E>)`; `InteractionContext` (`webagent4j-browser-api`) implements it,
so a scope is checked at compile time instead of by runtime `instanceof`. `webagent4j-observation-api`
similarly contains the detached model, options, renderers, diff, fingerprint, and batch-capture SPI
used by `IPage`. The observation engine depends on `IPage`, while the Playwright adapter supplies the
single bounded batch capture. A prepared action invokes the live element, evaluates explicit
verification objects, and returns an `ActionResult` with audit events.

Every command shares one backend-neutral resolve-and-validate step before it forks three ways:

```text
Locator
   |
   v
ActionTargetResolver
   |
   v
Preconditions
   |
   +------ plan() -----------------------------> IActionPlan (READY / BLOCKED)
   |                                                    |
   |                                              IActionPlan.execute()
   |                                              (revalidates from here)
   |                                                    |
   +------ dryRun() -----------------------------> ActionResult (DRY_RUN, no side effect)
   |
   +------ execute() --> ActionBackend --> Stabilization --> Observation --> Verification --> ActionResult
```

`plan()`, `dryRun()`, and a real `execute()` share the exact same `ActionTargetResolver` and
precondition evaluation, so they can never disagree about whether a target resolves or a precondition
holds. `plan()` never invokes the backend; it returns an immutable, inspectable `IActionPlan` instead
of an `ActionResult`. Its sole implementation, `DefaultActionPlan`, is package-private, so a plan can
only be obtained through `plan()`, never hand-built. `IActionPlan.execute()` never trusts that
snapshot - it reruns the whole pipeline from scratch, so a stale plan can never act on a semantically
different element, tolerate new ambiguity, or ignore a precondition that stopped holding. Structured
locator scopes follow the same rule, one level deeper: a scope built with
`InteractionContext.containingText(...)` is kept as a pending, backend-neutral definition and
re-resolved fresh on every individual polling attempt of a terminal operation's own wait
(`reference().resolve()` included), never frozen into one DOM node when the fluent chain is built and
never resolved only once before that wait begins - so a context that becomes ambiguous, disappears,
or is replaced by a semantically different region at any point between reference creation and
execution - including mid-wait, not only before or after it - blocks the action instead of silently
acting on stale state.

Resolution retries are separated from execution. Non-idempotent backend execution occurs at most
once, while stabilization and verification may safely poll read-only state. The Playwright adapter
implements the action, locator, and observation ports without leaking its native types.

`webagent4j-wait` is the one deterministic polling primitive underneath every read-only wait in the
locator, verification, and action pipelines - see [wait-and-stability.md](wait-and-stability.md).
It sits below every domain module, next to `webagent4j-common`, and knows nothing about DOM
elements, locators, or actions:

```text
webagent4j-common
        |
        v
 webagent4j-wait
   /    |    \
  /     |     \
Locator Verification Action
```

Locator resolution, verification polling, and action stabilization/postconditions each delegate
their deadline, polling-interval, and stability-window bookkeeping to this one engine instead of
each running its own timing loop; only the locator, verification, and action domains still decide
*what* is being waited for.

```text
Browser backend
      |
      v
PageSnapshot (bounded, backend-neutral)
      |
      v
Observation Engine
      |
      v
Semantic Model ----> Locator / Action / Extraction
```

## Dependency rules

- `core` cannot depend on Playwright or any future concrete browser backend.
- Public APIs cannot expose backend-native objects.
- `common`, `dom`, and the domain contracts have no framework dependency.
- `wait` depends only on `common` and the JDK; it cannot depend on `dom`, `locator`, `verification`,
  `action`, `browser`, `observation`, or Playwright, and knows nothing about any of those concepts.
- `action` and `verification` cannot depend on Playwright or another concrete browser backend.
- `webagent4j-crawler` and `webagent4j-crawler-api` cannot depend on Playwright, any browser
  backend, or an AI/LLM library; `webagent4j-crawler-api` cannot depend on the crawler engine
  module (only the reverse). See [http-crawler.md](http-crawler.md).
- `webagent4j-browser-crawler` depends only on backend-neutral contracts (`webagent4j-browser-api`,
  `webagent4j-crawler-api`, `webagent4j-wait`) and cannot depend on Playwright or an AI/LLM library -
  it navigates through `IPage`/`IBrowser`, never a native browser type. See
  [browser-crawler.md](browser-crawler.md).
- `webagent4j-plugin-api` depends only on locator and the JDK. Locator, core, workflow, and recording
  never depend on the plugin API, and no plugin API type depends on Playwright, another browser
  implementation, or an AI/LLM library.
- Public action contracts cannot depend on action implementation packages.
- No module depends on an AI, LLM, MCP, Spring, Jakarta EE, reactive, or dependency-injection framework.
- Cross-module implementations live in `internal` packages when they are not stable API.
- Cycles and the core/backend boundary are checked by ArchUnit.

## Future integrations

A future agent or MCP adapter must consume the same observation, locator, action, workflow, and
verification APIs as an ordinary application. It must never access Playwright directly:

```text
Optional decision system -> Observation -> IAction -> ActionResult -> Verification
                                      |
                                      v
                              public WebAgent4J API
```

The current project contains no concepts named prompt, token, model, chat, or AI in production APIs.
