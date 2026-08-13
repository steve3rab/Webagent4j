# Security Policy

## Supported versions

WebAgent4J is pre-1.0. Security fixes are applied to the latest development line only.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability. Submit a
[private security advisory](https://github.com/steve3rab/Webagent4j/security/advisories/new) instead.
Include affected versions, reproduction steps, impact, and any proposed mitigation. Do not include
credentials, personal data, or production secrets in the report.

Maintainers aim to acknowledge complete reports within seven days. Validation, remediation, and
coordinated disclosure timelines depend on severity and reproducibility. Please allow reasonable time
for a fix before public disclosure.

## Security model

V1 accepts local HTTP(S) targets by design so tests and developer tools work. Applications remain
responsible for target allowlists. URL schemes are validated, observations are bounded, public APIs do
not expose native Playwright objects, and future crawler/HTTP modules will add configurable SSRF,
redirect, response-size, and download-path policies before exposing those capabilities as complete.
