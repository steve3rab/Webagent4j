# Browser

`IBrowser` owns a native process and isolated browser context. It is not thread-safe and implements
`AutoCloseable`. `IPage` exposes navigation, history, HTML, screenshots, expression evaluation,
observation, locators, and actions without exposing Playwright.

`BrowserOptions` separates navigation, action, locator, and network-idle timeouts. V1 validates that
navigation targets are absolute HTTP(S) URLs. Local targets remain allowed for development and tests;
applications should enforce their own allowlists until the HTTP security-policy vertical is delivered.

The Playwright artifact registers `PlaywrightBrowserProvider` via `ServiceLoader`. Keep it on the
runtime classpath alongside `webagent4j-core`.
