# WebAgent4J

> A deterministic, backend-neutral foundation for semantic web automation in Java.

[![CI](https://github.com/steve3rab/Webagent4j/actions/workflows/ci.yml/badge.svg)](https://github.com/steve3rab/Webagent4j/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)

WebAgent4J is an early-stage Java 21 library for browser automation, semantic page observation,
accessible locators, explicit actions, and deterministic verification. The core has no AI, Spring,
Jakarta EE, reactive framework, or browser-engine dependency.

## Why WebAgent4J?

- Browser-neutral public contracts; Playwright is the first adapter.
- ARIA roles and accessible names are first-class locator inputs.
- Important actions return structured results and audit events.
- Immutable observations are ready for JSON serialization at application boundaries.
- Optional future integrations can consume the same public API without changing the core.

## Quick start

Prerequisites are Java 21 and Git. Maven is provided by the wrapper.

```bash
./mvnw clean verify
```

On Windows:

```powershell
.\mvnw.cmd clean verify
```

Import the BOM and add the core plus Playwright adapter:

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

```java
try (IBrowser browser = WebAgent.browser()
        .playwright()
        .chromium()
        .headless(true)
        .launch()) {
    IPage page = browser.open("https://example.com");
    Observation observation = page.observe();
    IElement link = page.find().link().named("More information...").first();
    ActionResult<Void> result = page.action()
            .click(link)
            .expectUrlContains("iana")
            .execute();
}
```

Semantic locators compose role, accessible name, and state without exposing the browser backend:

```java
IElement login = page.find()
        .button()
        .named("Sign in")
        .visible()
        .enabled()
        .first();

login.click();
```

Observe a page as an immutable, bounded semantic snapshot:

```java
Observation observation = page.observe();

observation.buttons()
        .forEach(button -> System.out.println(button.accessibleName()));
```

See [semantic observation](docs/observation.md) for the model, budgets, redaction, rendering,
fingerprinting, and diff API.

See [Getting started](docs/getting-started.md), [semantic locators](docs/locators.md),
[architecture](docs/architecture.md), the [module graph](docs/modules.md), and the
[roadmap](docs/roadmap.md).

## Status

Version `0.1.0-SNAPSHOT` implements the browser foundation, deterministic semantic locator engine,
and bounded semantic observation engine:
exact-first accessible discovery, conservative fuzzy fallback, ranking, ambiguity detection,
diagnostics, scoped queries, verified clicks, redacted immutable observations, semantic rendering,
fingerprinting, diffing, and local Playwright integration coverage. Modules for
later non-AI capabilities establish dependency boundaries but do not claim unimplemented APIs.

Contributions are welcome under the [contribution guide](CONTRIBUTING.md). Licensed under
[Apache License 2.0](LICENSE).
