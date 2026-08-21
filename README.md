# WebAgent4J

[![CI](https://github.com/steve3rab/Webagent4j/actions/workflows/ci.yml/badge.svg)](https://github.com/steve3rab/Webagent4j/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-ED8B00.svg?logo=openjdk&logoColor=white)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Project status](https://img.shields.io/badge/Status-Active%20development-orange.svg)](#project-status)

WebAgent4J is a deterministic, backend-neutral web automation foundation for Java 21. It combines
accessible semantic locators, explicit actions, verifiable outcomes, and bounded page observations
behind stable public contracts. Playwright is the first browser adapter.

> [!IMPORTANT]
> WebAgent4J is under active pre-1.0 development. Public APIs may evolve before the first stable
> release, and artifacts are not yet published to Maven Central.

## Why WebAgent4J

- **Accessible by design** — locate elements through ARIA roles, accessible names, labels, and state.
- **Deterministic resolution** — exact-first matching, conservative fallback, ranking, ambiguity
  detection, and explainable diagnostics.
- **Verified actions** — execute browser actions with explicit postconditions and structured results.
- **Semantic observations** — capture immutable, bounded, redacted snapshots for inspection, JSON
  rendering, fingerprinting, and diffing.
- **Backend-neutral APIs** — application code does not depend on native Playwright types.
- **Small core** — no AI SDK, application framework, or reactive runtime is required.

## Quick start

### Requirements

- Java 21 or newer
- Git

Maven is included through the Maven Wrapper.

```bash
git clone https://github.com/steve3rab/Webagent4j.git
cd Webagent4j
./mvnw clean verify
```

On Windows, run `mvnw.cmd clean verify` instead.

### Maven dependencies

Until artifacts are published, build and install the project locally with `./mvnw install`. Then
import the BOM and the modules needed by your application:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.webagent4j</groupId>
      <artifactId>webagent4j-bom</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.webagent4j</groupId>
    <artifactId>webagent4j-core</artifactId>
  </dependency>
  <dependency>
    <groupId>io.webagent4j</groupId>
    <artifactId>webagent4j-browser-playwright</artifactId>
    <scope>runtime</scope>
  </dependency>
</dependencies>
```

### Example

```java
try (IBrowser browser = WebAgent.browser()
        .playwright()
        .chromium()
        .headless(true)
        .launch()) {
    IPage page = browser.open("https://example.com");

    ActionResult<Void> result = page.action()
            .click(page.find()
                    .link()
                    .named("More information...")
                    .single())
            .expect(urlContains("iana"))
            .execute();
    result.throwIfFailed();
}
```

Additional runnable examples are available in
[`webagent4j-examples`](webagent4j-examples/src/main/java/io/webagent4j/examples).

`./mvnw clean verify` also builds the aggregated Javadoc (`target/reports/apidocs/index.html`) for
the full method-level API reference alongside the [Public API guide](docs/public-api.md).

The same aggregated Javadoc is published from `main` via GitHub Pages:

- Public Java API: <https://steve3rab.github.io/Webagent4j/api/latest/>
- Documentation site: <https://steve3rab.github.io/Webagent4j/>

`api/latest` tracks the current head of `main` and is republished on every push to it (see
[`.github/workflows/pages.yml`](.github/workflows/pages.yml)). Publishing requires the
repository's **Settings → Pages → Source** to be set to **GitHub Actions**; until that is
configured, these URLs are not yet live.

## Architecture

The repository is organized as a Maven multi-module build. Public contracts remain separate from
browser-specific implementations.

| Area | Modules | Purpose |
| --- | --- | --- |
| Public contracts | `browser-api`, `dom`, `locator-api`, `observation-api` | Stable application-facing types |
| Engines | `locator`, `observation`, `verification`, `action`, `workflow`, `recording` | Deterministic semantic behavior |
| Browser adapters | `browser-playwright` | Playwright-backed execution |
| Entry points | `core`, `cli`, `examples` | Configuration and usage |
| Quality | `testing`, `integration-tests`, `robustness-tests` | Fixtures, architecture rules, browser coverage, and adversarial validation |

See the [architecture guide](docs/architecture.md) and [module graph](docs/modules.md) for details.

## Documentation

- [Getting started](docs/getting-started.md)
- [Public API reference](docs/public-api.md) - which module to depend on, entry points, and contracts
- [Semantic locators](docs/locators.md)
- [Semantic observations](docs/observation.md)
- [Actions](docs/actions.md)
- [Verification](docs/verification.md)
- [Extraction](docs/extraction.md)
- [HTTP crawler](docs/http-crawler.md)
- [Browser crawler](docs/browser-crawler.md)
- [Workflows](docs/workflow.md)
- [Recording](docs/recording.md)
- [Testing](docs/testing.md)
- [Robustness benchmark](docs/robustness.md)
- [Known limitations](docs/limitations.md)
- [Roadmap](docs/roadmap.md)
- [Architecture decision records](docs/adr)

## Project status

The current development line implements the browser foundation, semantic locator engine, verified
actions, and semantic observation engine. The roadmap identifies modules that are architectural
placeholders and must not yet be treated as complete APIs.

Compatibility follows semantic versioning after `1.0.0`. Before 1.0, breaking changes are documented
in the [changelog](CHANGELOG.md) and release notes.

## Community

Contributions are welcome. Start with the [contribution guide](CONTRIBUTING.md), use
[GitHub Discussions](https://github.com/steve3rab/Webagent4j/discussions) for design questions, and
use [GitHub Issues](https://github.com/steve3rab/Webagent4j/issues) for reproducible defects and
focused proposals. General help is covered by the [support guide](SUPPORT.md).

Please report security issues privately according to the [security policy](SECURITY.md).

## License

WebAgent4J is available under the [Apache License 2.0](LICENSE).
