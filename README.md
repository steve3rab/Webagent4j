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

- Java 21
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

    IElement link = page.find()
            .link()
            .named("More information...")
            .visible()
            .first();

    ActionResult<Void> result = page.action()
            .click(link)
            .expectUrlContains("iana")
            .execute();

    Observation observation = page.observe();
    System.out.println(observation.toCompactText());
}
```

Additional runnable examples are available in
[`webagent4j-examples`](webagent4j-examples/src/main/java/io/webagent4j/examples).

## Architecture

The repository is organized as a Maven multi-module build. Public contracts remain separate from
browser-specific implementations.

| Area | Modules | Purpose |
| --- | --- | --- |
| Public contracts | `browser-api`, `dom`, `locator-api`, `observation-api` | Stable application-facing types |
| Engines | `locator`, `observation`, `verification`, `action` | Deterministic semantic behavior |
| Browser adapters | `browser-playwright` | Playwright-backed execution |
| Entry points | `core`, `cli`, `examples` | Configuration and usage |
| Quality | `testing`, `integration-tests` | Fixtures, architecture rules, and browser coverage |

See the [architecture guide](docs/architecture.md) and [module graph](docs/modules.md) for details.

## Documentation

- [Getting started](docs/getting-started.md)
- [Semantic locators](docs/locators.md)
- [Semantic observations](docs/observation.md)
- [Actions and verification](docs/actions.md)
- [Testing](docs/testing.md)
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
