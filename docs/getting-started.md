# Getting started

## Requirements

- Java 21 or later. Java 21 is the minimum supported runtime and bytecode baseline.
- Git for source builds.
- Internet access on the first source build so Maven dependencies and the Playwright browser revision can be installed.

Maven is supplied by the Maven Wrapper. A separate Maven installation is not required.

## Build from source

```bash
git clone https://github.com/steve3rab/Webagent4j.git webagent4j
cd webagent4j
./mvnw clean verify
```

On Windows PowerShell:

```powershell
.\mvnw.cmd clean verify
```

The first verification is slower because the Playwright-backed test modules install the pinned Chromium revision. Browser tests use local loopback fixtures and do not require public websites.

## Consume the Java libraries

Use the WebAgent4J BOM for supported BOM-managed artifacts. Replace `${webagent4j.version}` with the release you intend to use.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.webagent4j</groupId>
      <artifactId>webagent4j-bom</artifactId>
      <version>${webagent4j.version}</version>
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

Until a release is available from the repository you use, install the checked-out source locally with `./mvnw install` and use that exact project version. Do not assume a version is on Maven Central merely because a tag exists; release publication is verified separately in [release.md](release.md).

`webagent4j-common` is a supported low-level API/SPI artifact used by advanced integrations, but the current BOM does not manage it. A direct dependency on that artifact therefore needs an explicit version unless the BOM is changed before the release you consume. See [API stability](api-stability.md#artifact-policy).

## Launch a browser

```java
import io.webagent4j.core.WebAgent;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;

try (IBrowser browser = WebAgent.browser().launch()) {
    IPage page = browser.open("https://example.com");
    System.out.println(page.title());
}
```

`IBrowser` owns its browser context and implements `AutoCloseable`. Closing the browser closes pages and backend resources created inside that context. Live browser objects are caller-confined unless their API explicitly states otherwise.

## Run the CLI from a source build

After `./mvnw package`, use the JAR produced under `webagent4j-cli/target/`. The file name contains the project version; do not hard-code a snapshot version in scripts.

```bash
java -jar webagent4j-cli/target/webagent4j-cli-<version>.jar version
java -jar webagent4j-cli/target/webagent4j-cli-<version>.jar observe https://example.com
```

The CLI is a separate compatibility surface from the Java API. See [API stability](api-stability.md#cli-policy).

## Next steps

Read [Public API](public-api.md), then the domain guide for the operation you need. Review [Security model](security-model.md) before accepting untrusted URLs, plugins, metadata, or page content.
