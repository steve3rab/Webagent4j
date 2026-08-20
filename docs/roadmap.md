# Roadmap

No dates are implied. Each milestone requires implementation, tests, relevant integration coverage,
Javadoc, user documentation, and a green `clean verify` build.

- **0.1 Browser foundation:** first Playwright vertical, observations, semantic link resolution,
  verified clicks, CLI, and open-source build foundation.
- **0.2 Semantic locators:** more roles, labels, deterministic diagnostics, frames, and shadow DOM.
- **0.3 Observation engine:** richer landmarks, forms, tables, compact rendering, and JSON contracts.
- **0.4 Actions and verification:** typing, selection, waits, preconditions, and more postconditions.
- **0.5 Extraction:** deterministic text/attribute/value/list/table extraction reusing the existing
  locator engine, typed conversion, validation, provenance, and structured failures. See
  [docs/extraction.md](extraction.md).
- **0.6 HTTP crawler:** deterministic, sequential, backend-neutral HTTP crawler - frontier,
  normalization, deduplication, host/domain scope policy, redirect and retry handling, and
  structured failures. No browser, no `robots.txt` enforcement yet, no rate limiting, no
  persistent storage. See [docs/http-crawler.md](http-crawler.md).
- **0.7 Browser crawler:** dynamic pages, sessions, cancellation, and bounded concurrency - a
  deterministic browser crawler reusing `webagent4j-browser-api`/`webagent4j-wait`, one `IBrowser`
  session per crawl, top-level frame discovery, and concurrency bounded without sacrificing
  deterministic result ordering. See [docs/browser-crawler.md](browser-crawler.md).
- **0.8 Workflows:** variables, masked secrets, simple conditions, and structured results.
- **0.9 Recording and plugins:** record/replay foundation and small `ServiceLoader` extension points.
- **1.0 Stable non-AI API:** compatibility policy and production hardening.

Post-1.0 candidates include MCP and agent adapters, distributed crawling, more browser backends,
advanced observation, and opt-in self-healing. These remain optional consumers of the non-AI core.
