# Contributing to WebAgent4J

Thank you for helping build a reliable Java web automation foundation.

## Development setup

1. Install a Java 21 JDK.
2. Run `git config core.hooksPath .githooks`.
3. Run `./mvnw clean verify` (`.\mvnw.cmd clean verify` on Windows).

Use `./mvnw spotless:apply` before committing. The complete quality gate includes formatting,
Checkstyle, compilation, unit tests, integration tests, JaCoCo reports, architecture rules, packages,
and Javadoc.

## Pull requests

Every pull request must compile, pass all tests, follow formatting, include tests for behavior changes,
document public APIs with useful Javadoc, update user documentation when relevant, and preserve module
boundaries. Explain breaking changes explicitly.

Use Conventional Commits, for example:

- `feat(browser): add Chromium backend option`
- `fix(locator): handle duplicate accessible names`
- `docs(crawler): explain URL normalization`
- `test(action): cover failed postconditions`
- `refactor(core): simplify provider discovery`

Keep changes focused. Prefer composition, constructor injection, immutable values, explicit names, and
one primary responsibility per class. Interfaces begin with `I`, abstract classes with `A`, unit tests
end in `Test`, and integration tests end in `IT`.

By participating, you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
