# Adversarial hardening evidence

This document records the adversarial boundaries validated before the 1.0 release candidate. It is evidence, not a second source of API semantics. Current contracts are defined by [contracts.md](contracts.md), the domain guides, and Javadoc.

The hardening work deliberately added no new product capability, public extension point, Maven coordinate, runtime dependency, or Recording schema V1 field.

## Final invariants verified

### Numeric and deadline safety

- retry backoff factors must be finite;
- conversion/exponential delay growth saturates instead of overflowing into contradictory delay state;
- bounded ratios/scores reject `NaN`/infinite values;
- wait budgets remain consistent across signed `nanoTime()` rollover;
- elapsed strategy/action/event durations use monotonic time while wall-clock time remains for absolute timestamps only;
- adapter inspection work scales from the caller's remaining budget and does not introduce independent “comfort floor” windows that multiply the deadline.

### Interruption

- wait interruption preserves the caller thread's interrupted status;
- action interruption before backend invocation yields `CANCELLED/NOT_EXECUTED/INTERRUPTED`;
- action interruption after invocation/possible side effect yields `CANCELLED/REAL/INTERRUPTED`;
- planning interruption yields a BLOCKED plan with `INTERRUPTED` and no backend invocation.

### Fail-closed backend classification

- a Playwright timeout/race is not absence merely because the inspected handle became inconvenient to use;
- where disappearance is a known race, a fresh current-state check must prove absence/frame unavailability before conversion to not-found/pending;
- if the target still exists, the original backend error propagates;
- if the recheck itself fails opaquely, the original error propagates with recheck context rather than fabricating absence;
- incidental error-message text is not treated as a typed disappearance signal.

### Structured-scope identity

Several intermediate designs were intentionally rejected because they could violate wrong-target or resource-retention invariants. The final design is characterized by the following externally relevant properties:

- semantic cardinality (0/1/N) is checked before a physical binding guard, so a late duplicate cannot be collapsed into uniqueness;
- an already-bound structured scope follows the same physical element across DOM reordering;
- physical replacement cannot silently inherit the old scope merely by occupying its old index or copying semantics;
- application-controlled DOM attributes/globals are not the source of physical candidate identity;
- scope identity bookkeeping does not mutate application DOM;
- per-live-element selector-engine state is constant-size rather than an unbounded history of tokens;
- old live scopes do not expire because unrelated later resolutions exceeded an arbitrary retention cap;
- transient binding/lease state is promoted to stable physical identity and does not accumulate historical tokens;
- containment/identity-sensitive checks use backend-controlled/trusted primitives rather than page-overridable aliases where hardening requires it.

### Resource ownership

- temporary Playwright element handles acquired for inspection are disposed deterministically;
- cleanup is attempted in `finally` and a cleanup failure does not replace the primary semantic/backend result;
- no public closeable lifecycle was invented solely to repair an internal temporary-resource leak;
- browser/browser-crawler ownership remains as documented in the public contract.

### Workflow/recording/plugin boundaries

- impossible workflow/action projections are rejected at construction/decode;
- Recording V1 remains strict and unchanged in field/version shape;
- raw workflow values/action values/throwables stay outside recordings;
- caller metadata remains an explicit verbatim trust boundary;
- plugin discovery is explicit/all-or-nothing, deterministic, and trusted in-process;
- no arbitrary plugin callback retry or sandbox claim was introduced.

## Adversarial matrix

| Surface | Boundary | Expected safe outcome |
| --- | --- | --- |
| Common/wait | overflow, zero/huge timeouts, rollover, interruption | bounded deterministic arithmetic or typed interruption |
| Locator | ambiguity, dynamic replacement, NaN values, backend failure | unique target only when justified; otherwise typed failure |
| Playwright scopes | reordering, replacement, late duplicate, adoption/context race | preserve physical identity or fail closed; never wrong target |
| Frames | detachment/replacement/cross-origin/URL race | current typed absence/ambiguity/backend failure |
| Actions | expired budget, interruption, retry, plan revalidation | no hidden repeated side effect; exact execution mode |
| Observation/extraction | truncation, redaction, detached element, conversion/validation | bounded result or explicit failure |
| HTTP crawler | cycles, redirect/retry/size/depth/page limits | deterministic bounded partial result/failure |
| Browser crawler | cancellation, cleanup, dynamic page, out-of-scope redirect | deterministic single-lane outcome and cleanup |
| Workflow | preflight/runtime failure, secret redaction | one fail-fast trace, no hidden retry |
| Recording | malformed/duplicate/unknown/trailing/impossible JSON | strict reject, no schema fallback |
| Plugins | invalid/duplicate provider/strategy metadata, runtime callback failure | all-or-nothing load or propagated runtime failure |

## Evidence policy

A production correction should be reproduced by a focused deterministic test before the minimal fix, then verified through the affected module, integration tests, and standard reactor. Timing tests should use fake monotonic time where possible; real Playwright races should assert classification and side-effect oracles rather than host-speed thresholds.

Do not maintain exact global test counts, coverage percentages, pull-request heads, or intermediate failed implementation designs in this document. Those belong to CI history and review discussion, not long-lived release evidence.

## Release verdict rule

A release candidate is not accepted with any known P0/P1 hardening finding. Required checks must be green on the exact candidate SHA and the SHA must be re-fetched immediately before the final verdict.
