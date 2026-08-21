/**
 * Engine internals for {@link io.webagent4j.browsercrawler.BrowserCrawler}: the FIFO frontier, the
 * synchronized claim gate, scope evaluation, DOM link discovery, page stability waiting, and URL
 * normalization.
 *
 * <p>"Internal" by convention, not by Java-enforced access - these types are {@code public} because
 * {@link io.webagent4j.browsercrawler.BrowserCrawler} in the parent package must call them, exactly
 * as {@code io.webagent4j.crawler.internal} types are public for {@code HttpCrawler}'s benefit.
 * None of this package is part of the module's supported public API; see {@code
 * docs/browser-crawler.md}.
 */
package io.webagent4j.browsercrawler.internal;
