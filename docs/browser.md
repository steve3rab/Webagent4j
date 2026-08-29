# Browser lifecycle

`IBrowser`, `IPage`, and `IFrame` are backend-neutral live browser contracts. The default project adapter is Playwright, discovered through `ServiceLoader`; public APIs do not expose native Playwright objects.

## Launch and ownership

```java
try (IBrowser browser = WebAgent.browser().launch()) {
    IPage page = browser.open("https://example.com");
    System.out.println(page.title());
}
```

The caller owns a launched `IBrowser`. It owns a browser context and the pages created inside it. Closing the browser closes its pages/context/backend resources. Individual page objects may also be closed when their lifecycle ends earlier.

If an operation receives a caller-owned browser, it does not assume ownership unless its API explicitly says so. The browser crawler is the main example: it closes the supplied browser only when `closeBrowserOnCompletion(true)` requests that transfer.

## Threading

Browsers, pages, frames, live elements, and action builders are caller-confined. Do not operate on the same live browser/page concurrently unless a specific implementation explicitly provides a stronger guarantee.

Immutable results, definitions, options, recordings, and detached observations have separate shareability rules and do not inherit page confinement merely because they were created from a page.

## Pages

`IBrowser` supports creating a new page, opening/navigating to a URL, listing current pages, and obtaining the current page. `IPage` exposes navigation/history, title/URL/HTML/screenshot/evaluation, semantic find/locate, observation, extraction, actions, verification context, and frame entry points without exposing backend-native types.

`browser.open(url)` creates a page and navigates it. If navigation fails, the just-created page is closed rather than leaked.

## Navigation and URLs

Navigation targets are validated as absolute HTTP(S) URLs by the supported browser path. This is a syntax/scheme contract, **not** a general network security policy. Localhost, loopback, private-network, or otherwise sensitive HTTP(S) destinations are possible by design. Applications accepting untrusted targets must enforce their own allowlist/network policy; see [Security model](security-model.md#network-and-ssrf-boundary).

Navigation timeouts are backend-bounded where a timeout-aware operation is exposed. A timeout means the operation did not satisfy its deadline; it must not be reinterpreted as a backend crash or as proof that an unrelated element is absent.

## Frames

Frames are resolved through backend-neutral `IFrameLocator` criteria. A resolved frame is a live document scope for find/action/extraction operations. Frame lookup fails closed on ambiguity and distinguishes disappearance from opaque backend failure. Cross-origin frames remain subject to browser security but can be entered through the supported backend abstraction when Playwright can access them.

## JavaScript evaluation

`IPage.evaluate(String)` is an explicit escape hatch that executes caller-supplied JavaScript in the page. It returns `Object` because JavaScript values are dynamically typed.

Evaluation is not a sandbox or a safe-data channel. Do not interpolate secrets or untrusted script fragments into JavaScript strings. Page/application code is outside WebAgent4J's trusted logging/redaction guarantees.

## Browser types

The Playwright adapter implements Chromium, Firefox, and WebKit launch paths. `develop` carries nightly and release qualification infrastructure covering all three engines through this same public browser path, and exact-head evidence has observed all three passing the complete adversarial corpus together, on Linux; the release workflow gates every engine equally before publication. Engine qualification and operating-system qualification are independent axes, so this is not evidence that any given engine has been qualified on every operating system. See [Support matrix](support-matrix.md#browser-and-robustness-qualification-by-operating-system) for the exact current evidence.

## Provider discovery

`webagent4j-browser-playwright` registers `PlaywrightBrowserProvider` through Java `ServiceLoader`. Applications normally keep the adapter on the runtime classpath and launch through `WebAgent` instead of constructing the provider directly.

## Security responsibilities

The browser API does not:

- block private-network HTTP(S) targets by default;
- enforce `robots.txt`;
- bypass authentication, CAPTCHA, anti-bot controls, or consent;
- make arbitrary evaluated JavaScript safe;
- make page-provided text trustworthy for logs;
- sandbox plugins or application callbacks.

Use [Security model](security-model.md) and the relevant domain guide for the exact boundary.
