/**
 * Backend-neutral HTTP crawler contracts - depends only on {@code webagent4j-common}: no HTTP
 * client, no HTML parser, no Playwright.
 *
 * <p>{@link io.webagent4j.crawler.api.CrawlRequest} is immutable and fully validated at
 * construction; {@link io.webagent4j.crawler.api.ICrawler#crawl(CrawlRequest)} is the entire public
 * surface an engine implements (currently only {@code HttpCrawler} in {@code webagent4j-crawler}).
 * {@link io.webagent4j.crawler.api.CrawlResult} carries {@link
 * io.webagent4j.crawler.api.CrawledPage}s, structured {@link
 * io.webagent4j.crawler.api.CrawlFailure}s, {@link io.webagent4j.crawler.api.CrawlStatistics}, and
 * a {@link io.webagent4j.crawler.api.CrawlTerminationReason} - no field of {@code CrawlStatistics}
 * is defined in terms of another; each has its own independent, precise definition (see its own
 * class documentation).
 *
 * <p>{@link io.webagent4j.crawler.api.IUrlNormalizer}, {@link
 * io.webagent4j.crawler.api.ICrawlScopePolicy}, and {@link
 * io.webagent4j.crawler.api.ICrawlDeduplicator} are the extension points a custom engine or policy
 * would implement. This module cannot depend on Playwright, any browser backend, or an AI/LLM
 * library. See {@code docs/http-crawler.md} for the full traversal pipeline, redirect/retry
 * semantics, and the determinism contract.
 */
package io.webagent4j.crawler.api;
