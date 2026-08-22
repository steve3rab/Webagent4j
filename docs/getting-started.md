# Getting started

## Requirements

- Java 21 or later
- Git
- Internet access on the first build so Maven and the Playwright Chromium binary can be downloaded

Maven itself is supplied through Maven Wrapper.

Java 21 is the minimum supported version. Maven Enforcer accepts JDK 21 and all later feature
releases (`[21,)`), while the compiler uses `--release 21` so published classes remain compatible
with Java 21. CI deliberately runs on Java 21 to verify that minimum.

```bash
git clone <repository-url> webagent4j
cd webagent4j
./mvnw clean verify
```

Windows PowerShell users run `.\mvnw.cmd clean verify`. The first verification is slower because the
integration-test module installs the Chromium revision paired with the pinned Playwright version.

## Run the CLI

```bash
java -jar webagent4j-cli/target/webagent4j-cli-0.1.0-SNAPSHOT.jar version
java -jar webagent4j-cli/target/webagent4j-cli-0.1.0-SNAPSHOT.jar observe https://example.com
java -jar webagent4j-cli/target/webagent4j-cli-0.1.0-SNAPSHOT.jar screenshot https://example.com -o screenshots/example.png
```

## Run the example

After `install`, use the exec plugin from the examples module:

```bash
./mvnw -pl webagent4j-examples -am install -DskipTests
./mvnw -pl webagent4j-examples exec:java -Dexec.mainClass=io.webagent4j.examples.VerifiedNavigationExample
```

Use try-with-resources for every browser. Pages belong to the browser context and are closed with it.
