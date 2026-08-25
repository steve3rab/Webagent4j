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
| Firefox | Yes | No full adversarial corpus | Tier 2: available, best-effort until promoted |
| WebKit | Yes | No full adversarial corpus | Tier 2: available, best-effort until promoted |

The Playwright adapter can launch all three engines, but the deterministic wrong-target/ambiguity corpus currently gates Chromium. Firefox or WebKit must not be described as equivalently qualified until the same robustness expectations run reliably for them.

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
