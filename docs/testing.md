# Testing

Unit tests end with `Test` and run under Surefire. Integration tests end with `IT` and run under
Failsafe. The Playwright integration test starts a local JDK HTTP server on a random loopback port; it
does not depend on a public website. It covers browser launch, navigation, observation, semantic
location, click, URL verification, and cleanup.

ArchUnit checks package cycles, interface naming, and the core/Playwright boundary. JaCoCo writes
module reports and an aggregate report under `webagent4j-integration-tests/target/site/jacoco-aggregate`.
Source-bearing modules with direct tests must keep at least 70% line coverage. The Playwright adapter is
measured in the aggregate report because its coverage comes from the separate integration-test module.
Testcontainers is aligned in dependency management and available to integration tests, but V1 starts no
unused container.

Run `./mvnw clean verify`. Use `./mvnw spotless:apply` to format changes.
