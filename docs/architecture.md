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

`webagent4j-locator-api` breaks the dependency cycle required by scoped queries: it contains generic
fluent contracts and immutable definitions, `IElement` exposes `find()`, and the locator engine depends
on the DOM contract for scoring. `webagent4j-observation-api` similarly contains the detached model,
options, renderers, diff, fingerprint, and batch-capture SPI used by `IPage`. The observation engine
depends on `IPage`, while the Playwright adapter supplies the single bounded batch capture. An action plan invokes
the live element, evaluates explicit verification objects, and returns an `ActionResult` with audit
events.

Every command uses one backend-neutral lifecycle:

```text
Locator
   |
   v
ActionTargetResolver
   |
   v
Preconditions
   |
   v
ActionBackend
   |
   v
Stabilization
   |
   v
Observation
   |
   v
Verification
   |
   v
ActionResult
```

Resolution retries are separated from execution. Non-idempotent backend execution occurs at most
once, while stabilization and verification may safely poll read-only state. The Playwright adapter
implements the action, locator, and observation ports without leaking its native types.

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
Semantic Model ----> Locator / Action / future Extraction
```

## Dependency rules

- `core` cannot depend on Playwright or any future concrete browser backend.
- Public APIs cannot expose backend-native objects.
- `common`, `dom`, and the domain contracts have no framework dependency.
- `action` and `verification` cannot depend on Playwright or another concrete browser backend.
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
