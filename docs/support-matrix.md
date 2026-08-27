# Support matrix

This matrix separates implementation availability from release-gated support. “Implemented” means code exists; “release-gated” means the repository's required verification is expected to block a release when that surface regresses.

## Java and build environment

| Surface | Status | Notes |
| --- | --- | --- |
| Java 21 | Release baseline | Minimum supported runtime and compiler `--release` target |
| Later JDK feature releases | Supported | Maven Enforcer accepts Java 21 or later; Java 21 remains the compatibility floor |
| Maven Wrapper | Supported build path | Prefer `./mvnw` / `mvnw.cmd` so the project controls Maven distribution |
| Linux | Release-gated | Primary CI environment and Chromium robustness environment |
| Windows | Nightly verified | Standard reactor is exercised on the nightly OS matrix |
| macOS | Nightly verified | Standard reactor is exercised on the nightly OS matrix |
| Docker | Release/CI packaging path | Official Playwright Java image is used for Linux browser prerequisites |

Passing on one operating system is not evidence that a browser backend is fully qualified on every operating system. Browser and OS axes are independent.

## Browser backend

| Engine | Implemented | Deterministic robustness gate | 1.0 support tier |
| --- | ---: | ---: | --- |
| Chromium | Yes | Yes | Tier 1: release-gated |
| Firefox | Yes | Nightly and release qualification infrastructure exists; not yet observed passing the full corpus on an exact head | Tier 2: implemented in the Playwright adapter; not yet promoted to release qualification |
| WebKit | Yes | Nightly and release qualification infrastructure exists; not yet observed passing the full corpus on an exact head | Tier 2: implemented in the Playwright adapter; not yet promoted to release qualification |

The Playwright adapter can launch all three engines, but the deterministic wrong-target/ambiguity corpus has so far only gated Chromium. `develop` now contains nightly-matrix and release-verification infrastructure that exercises the identical adversarial corpus (element and frame scenarios) against Firefox and WebKit too, through the same public browser path -- but infrastructure existing is not qualification evidence. Release verification is configured to require all three engines to pass before a future release can publish. Scheduled nightly activation is separate: GitHub only triggers the `schedule` event from a workflow definition committed on the repository's default branch, so the three-engine nightly matrix does not actually run on a schedule until this workflow file also reaches that branch. Firefox or WebKit must not be described as equivalently qualified until the corpus has actually been observed passing, with zero wrong targets, on the same intended code state.

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
