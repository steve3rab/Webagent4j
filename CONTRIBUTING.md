# Contributing to WebAgent4J

Thank you for helping build a reliable Java web automation foundation. Contributions of code,
tests, documentation, design feedback, and reproducible bug reports are welcome.

## Before you start

- Search existing issues and pull requests before opening a new one.
- Use GitHub Discussions for questions and early design exploration.
- Open an issue before starting a large feature or breaking API change.
- Never include credentials, tokens, cookies, private URLs, personal data, or proprietary page
  content in issues, tests, fixtures, logs, commits, or pull requests.

## Development setup

1. Install a Java 21 JDK and Git.
2. Fork and clone the repository.
3. Configure the repository hooks with `git config core.hooksPath .githooks`.
4. Run `./mvnw clean verify` (`mvnw.cmd clean verify` on Windows).

Maven is supplied by the wrapper. The quality gate includes formatting, Checkstyle, compilation,
unit tests, browser integration tests, JaCoCo reports, architecture rules, packaging, and Javadoc.

Use `./mvnw spotless:apply` before committing when formatting needs correction.

## Design and coding conventions

- Target Java 21 and keep public contracts backend-neutral.
- Prefer immutable values, composition, constructor injection, explicit names, and one primary
  responsibility per class.
- Preserve Maven module boundaries and avoid dependency cycles.
- Public APIs require useful Javadoc and focused tests.
- Interfaces begin with `I`, abstract classes with `A`, unit tests end in `Test`, and integration
  tests end in `IT`.
- Do not expose native browser-backend objects from public APIs.

## Commits

Keep commits focused and write imperative Conventional Commit messages, for example:

- `feat(browser): add Chromium launch option`
- `fix(locator): handle duplicate accessible names`
- `docs(observation): explain redaction defaults`
- `test(action): cover failed postconditions`
- `refactor(core): simplify provider discovery`

Configure Git with a public GitHub noreply address if you do not want a personal email embedded in
commit metadata. See GitHub's documentation for keeping an email address private.

## Pull requests

Pull requests should:

- describe the problem, approach, and user impact;
- link the relevant issue when applicable;
- include tests for changed behavior;
- update documentation for user-facing or public API changes;
- call out compatibility and migration concerns;
- pass `./mvnw clean verify`; and
- remain small enough to review effectively.

Maintainers may ask for changes to API design, test coverage, documentation, or commit structure.
Reviews focus on correctness, deterministic behavior, security, maintainability, and module
boundaries.

## Branch protection for `main`

`main` must be configured with the following GitHub branch protection rule (Settings → Branches →
branch protection rule for `main`), so a red CI run can never be merged again:

- Require a pull request before merging - no direct pushes to `main`.
- Require status checks to pass before merging, with these checks marked required:
  - `CI / Java 21 / Linux`
  - `CodeQL`
- Require branches to be up to date with `main` before merging (so a check that passed against a
  stale base cannot merge a since-broken combination).
- Dismiss stale pull request approvals when new commits are pushed.
- Include administrators, so the rule applies uniformly.

These settings live in repository configuration, not in workflow YAML - they cannot be enforced
from a workflow file, and a workflow must never attempt to emulate them (for example, by failing a
step conditionally to imitate a required check). If this rule is not currently configured, a
repository administrator needs to add it under Settings → Branches.

## Reporting security issues

Do not report vulnerabilities in public issues. Follow the [security policy](SECURITY.md).

By participating, you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
