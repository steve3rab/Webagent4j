# Support matrix

This matrix separates implementation availability from release-gated support. “Implemented” means code exists; “release-gated” means the repository's required verification is expected to block a release when that surface regresses.

It deliberately keeps five axes apart: Java/runtime support, operating-system verification, browser implementation, deterministic adversarial-robustness qualification, and release gating. Passing on one axis is never evidence for another - in particular, an operating system passing the standard reactor is not evidence that the adversarial robustness corpus was exercised there, and a browser engine passing the adversarial corpus on one operating system is not evidence it was exercised on another. Do not infer an untested combination (for example "Firefox on Windows") from two separately true facts (for example "Firefox robustness passed on Linux" and "Windows passed the standard reactor").

## Java and build environment

| Surface | Status | Notes |
| --- | --- | --- |
| Java 21 | Release baseline | Minimum supported runtime and compiler `--release` target |
| Later JDK feature releases | Supported | Maven Enforcer accepts Java 21 or later; Java 21 remains the compatibility floor |
| Maven Wrapper | Supported build path | Prefer `./mvnw` / `mvnw.cmd` so the project controls Maven distribution |
| Docker | Release/CI packaging path | Official Playwright Java image is used for Linux browser prerequisites |

## Operating-system verification (standard reactor)

| OS | Status | What this proves |
| --- | --- | --- |
| Linux | Release-gated | Primary CI environment; standard `clean verify` reactor (build, unit/integration tests, coverage gates) runs on every PR and nightly |
| Windows | Nightly verified | Standard `clean verify` reactor runs on the nightly OS matrix |
| macOS | Nightly verified | Standard `clean verify` reactor runs on the nightly OS matrix |

This axis proves the standard reactor - not the adversarial robustness corpus - builds and passes on that operating system. See [Browser × robustness qualification](#browser--deterministic-robustness-qualification) below for which operating system the adversarial corpus itself has actually been exercised on.

## Browser backend (implementation)

| Engine | Implemented | Notes |
| --- | ---: | --- |
| Chromium | Yes | Playwright adapter launch path |
| Firefox | Yes | Playwright adapter launch path |
| WebKit | Yes | Playwright adapter launch path |

Implementation availability alone is not qualification evidence; see the next section.

## Browser and robustness qualification by operating system

| Engine | Full 100-scenario adversarial corpus | Operating system(s) exercised | Release gate |
| --- | --- | --- | --- |
| Chromium | Passing | Linux (bare runner and Docker image) | Release-gated |
| Firefox | Passing | Linux (bare runner) | Release-gated |
| WebKit | Passing | Linux (bare runner) | Release-gated |

`.github/workflows/release.yml`'s `verify` job runs the complete adversarial corpus sequentially for `chromium`, `firefox`, and `webkit` (`-Probustness -Drobustness.browser=<engine> verify`, one full Maven reactor build per engine) and fails closed on the first engine that does not pass zero-wrong-target with no retry - so all three engines are equally release-gated by construction, not just Chromium. `.github/workflows/nightly.yml` runs the same three-engine matrix, independently per engine (`fail-fast: false`), on `ubuntu-latest` inside the official Playwright Java container.

Independent manual (`workflow_dispatch`) Nightly runs against an exact `develop` head have completed with the full corpus passing, with zero wrong targets, for Chromium, Firefox, and WebKit together on the same commit. This is exact-head qualification evidence, not merely infrastructure existing; see the repository's Nightly platforms workflow runs for the current evidence rather than a commit reference recorded here, which would otherwise go stale as `develop` advances.

This evidence is Linux-only: the adversarial robustness corpus itself is not currently run on the nightly Windows/macOS jobs (those jobs run the standard reactor, not `-Probustness`), and the Docker `robustness` image target runs the corpus without selecting an engine, which defaults to Chromium only. Do not read "Firefox passes on Linux" together with "the standard reactor passes on Windows" as "Firefox is qualified on Windows" - that combination has not been exercised.

Scheduled (cron) nightly activation is separate from the manual-dispatch evidence above: GitHub only triggers a workflow's `schedule` event from the workflow definition committed on the repository's default branch (`main`). Whether the automatic nightly schedule currently exercises the three-engine `robustness` matrix therefore depends on whether `main`'s own copy of `nightly.yml` already contains that job - check `main`'s copy directly rather than assuming. Until it does, the three-engine matrix runs only via manual `workflow_dispatch` against `develop` (as above) or as part of `release.yml`'s own release-candidate verification.

## Functional surfaces

| Capability | 1.0 status |
| --- | --- |
| Browser lifecycle and pages | Supported |
| Semantic locators, scopes, frames | Supported |
| Semantic observation | Supported |
| Verified actions and action plans | Supported |
| Verification and deterministic waits | Supported |
| Extraction | Supported |
| HTTP crawler | Supported, sequential |
| Browser crawler | Supported, single-lane |
| Workflows | Supported, sequential fail-fast |
| Recording JSON V1 and offline comparison | Supported |
| Trusted custom locator plugins | Supported opt-in SPI |
| Live automatic replay from recordings | Not implemented |
| General persistence/storage subsystem | Not implemented |
| General SSRF firewall | Not implemented |
| `robots.txt` enforcement | Not implemented |
| CAPTCHA/anti-bot bypass | Not implemented |
| AI/OCR/computer-vision fallback | Not implemented |

See [Known limitations](limitations.md) for the precise boundaries.
