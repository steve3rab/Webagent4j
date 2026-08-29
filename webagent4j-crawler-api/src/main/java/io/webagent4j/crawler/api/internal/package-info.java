/**
 * Shared validation internals for the crawler API and its {@code webagent4j-crawler} implementation
 * - currently just {@link io.webagent4j.crawler.api.internal.HttpHeaderValidation}, the one
 * framework-owned definition of a valid caller-supplied crawler HTTP header.
 *
 * <p>"Internal" by convention, not by Java-enforced access - these types are {@code public} because
 * {@code webagent4j-crawler} (a separate module depending on this one) must call them, exactly as
 * {@code io.webagent4j.crawler.internal} types are public for {@code HttpCrawler}'s benefit. None
 * of this package is part of the module's supported public API; it is excluded from the API
 * compatibility gate like every other {@code .internal} package in this project.
 */
package io.webagent4j.crawler.api.internal;
