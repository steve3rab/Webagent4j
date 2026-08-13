# Security Policy

## Supported versions

WebAgent4J is pre-1.0. Security fixes are applied to the latest development line only.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability. Use GitHub's private security advisory
feature for this repository. Include affected versions, reproduction steps, impact, and any proposed
mitigation. Maintainers will acknowledge a complete report as soon as practical and coordinate a fix
and disclosure.

## Security model

V1 accepts local HTTP(S) targets by design so tests and developer tools work. Applications remain
responsible for target allowlists. URL schemes are validated, observations are bounded, public APIs do
not expose native Playwright objects, and future crawler/HTTP modules will add configurable SSRF,
redirect, response-size, and download-path policies before exposing those capabilities as complete.
