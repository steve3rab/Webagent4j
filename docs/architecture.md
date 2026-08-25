# Architecture

WebAgent4J uses ports and adapters at module scale. Public browser/domain contracts are backend-neutral; Playwright is one runtime adapter.

```text
Application / CLI
       |
       v
      Core
       |
       v
   Browser API
   /   |   |   \
 DOM Locator Action Observation API
  |    |     \      |
  | Locator API Verification
  |             \   |
  +---- Extraction  Observation Engine

Playwright adapter -> browser/domain contracts
```

The exact Maven edges are listed in [modules.md](modules.md).

## Dependency rules

- Core does not depend on Playwright or another concrete backend.
- Supported public APIs do not expose backend-native objects.
- `wait` is JDK/common-level infrastructure and knows nothing about DOM, locators, browsers, or actions.
- Action and verification do not depend on Playwright.
- HTTP crawler contracts/engine do not depend on browser backends or AI libraries.
- Browser crawler depends on backend-neutral browser/crawler/wait contracts and not on Playwright directly.
- Plugin discovery is opt-in: `plugin-api -> locator`, never `locator -> plugin-api`.
- Recording remains independent of browser, crawler, Playwright, and plugin discovery.
- No production module requires an AI/LLM, MCP, Spring, Jakarta EE, reactive, or dependency-injection framework.
- Unsupported cross-package implementation helpers use `internal` packages where practical.
- Important dependency boundaries are enforced by architecture tests.

## One wait primitive

`webagent4j-wait` owns monotonic deadline, polling, sleeping, interruption, and stability-window mechanics. Locator resolution, verification, and action stabilization/postconditions adapt their read-only probes to that one primitive instead of running parallel timing engines.

```text
             WaitEngine / WaitBudget
               /      |       \
         Locator  Verification  Action
```

The domain still decides which failures are retryable. `WaitEngine` itself never guesses that an exception means “not found yet”.

## Action safety architecture

```text
semantic target resolution
        |
preconditions
  /       |        \
plan   dry-run    execute
 |         |          |
no side   no side   backend invocation (at most once)
effect    effect       |
                    stabilization -> observation -> verification
```

`plan()`, `dryRun()`, and `execute()` share target/precondition logic. A plan is a snapshot and `IActionPlan.execute()` revalidates from scratch. A plan is single-use so a caller cannot accidentally execute one non-idempotent plan twice.

Potentially side-effecting backend execution is kept outside wait loops. Read-only resolution/stabilization/verification may poll; the side effect is not automatically replayed because a later observation remains pending.

## Dynamic semantic scopes

Structured scopes are definitions, not cached DOM indices. They are re-resolved against live state during terminal operations and each poll of a waiting terminal operation. The Playwright adapter preserves the physical identity needed to protect a live already-resolved scope from DOM reordering or substitution while still checking semantic cardinality first so a late duplicate remains ambiguous.

Application-controlled attributes, DOM order, visible text, or page globals are not trusted as physical identity. Physical-identity bookkeeping is backend-internal and does not mutate application DOM.

## Observation architecture

Browser adapters provide a bounded backend-neutral snapshot. The observation engine transforms that snapshot into detached immutable semantic values. Reading an `Observation` never calls the live page again.

```text
Browser -> PageSnapshot -> ObservationEngine -> Observation
                                      |
                                reference intent
                                      |
                                later re-resolution
```

## Plugin architecture

```text
Application explicitly calls PluginLoader
              |
ServiceLoader<ILocatorStrategyProvider>
              |
validated immutable PluginRegistry
              |
LocatorStrategyRegistry -> LocatorEngine
```

No default browser/locator/workflow/recording/crawler path performs plugin discovery. Plugin code is trusted in-process Java and is not sandboxed.

## Persistence boundary

The only stable framework-owned serialized persistence format is workflow Recording JSON V1. Other public values and native Java serialization are not general persistence contracts.
