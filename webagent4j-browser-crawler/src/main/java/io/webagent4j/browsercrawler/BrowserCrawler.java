package io.webagent4j.browsercrawler;

import io.webagent4j.browser.ConditionTimeoutException;
import io.webagent4j.browser.IPage;
import io.webagent4j.browser.NavigationTimeoutException;
import io.webagent4j.browsercrawler.internal.BrowserCrawlFrontier;
import io.webagent4j.browsercrawler.internal.BrowserCrawlTask;
import io.webagent4j.browsercrawler.internal.BrowserUrlNormalizer;
import io.webagent4j.browsercrawler.internal.ClaimGate;
import io.webagent4j.browsercrawler.internal.LinkDiscoverer;
import io.webagent4j.browsercrawler.internal.PageStabilityWaiter;
import io.webagent4j.browsercrawler.internal.RawLink;
import io.webagent4j.browsercrawler.internal.ScopeEvaluator;
import io.webagent4j.crawler.api.CrawlDecision;
import io.webagent4j.crawler.api.CrawlDecisionType;
import io.webagent4j.crawler.api.DiscoveredLink;
import io.webagent4j.crawler.api.IUrlNormalizer;
import io.webagent4j.crawler.api.LinkKind;
import io.webagent4j.observation.Observation;
import io.webagent4j.observation.ObservationOptions;
import io.webagent4j.observation.ObservationStatistics;
import io.webagent4j.observation.ObservationTruncation;
import io.webagent4j.wait.WaitBudget;
import io.webagent4j.wait.WaitEngine;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The deterministic, single-lane browser crawler engine - the only implementation of {@link
 * IBrowserCrawler}, mirroring how {@code HttpCrawler} is the only implementation of {@code
 * ICrawler}.
 *
 * <p>Each {@link #crawl(BrowserCrawlRequest)} call constructs a private, per-call {@code Session}
 * holding all mutable state, so one {@code BrowserCrawler} instance is stateless and safe to reuse
 * across calls - the same pattern {@code HttpCrawler} uses. See {@code docs/browser-crawler.md} for
 * the full navigation/stability/failure pipeline and why this engine deliberately does not attempt
 * physical navigation concurrency: {@link io.webagent4j.browser.IBrowser} and {@link
 * io.webagent4j.browser.IPage} are both documented as not thread-safe, and one caller-supplied
 * {@code IBrowser} is the crawl session (cookies/storage/auth all live on it) - so every backend
 * call this engine makes happens on the single thread that calls {@link #crawl}, never offloaded to
 * a worker pool.
 */
public final class BrowserCrawler implements IBrowserCrawler {

    private final WaitEngine waitEngine;

    /** Creates an engine using a real system clock and sleeper. */
    public BrowserCrawler() {
        this(new WaitEngine());
    }

    /** Creates an engine using the given {@link WaitEngine} - primarily for deterministic tests. */
    public BrowserCrawler(WaitEngine waitEngine) {
        this.waitEngine = waitEngine;
    }

    @Override
    public BrowserCrawlResult crawl(BrowserCrawlRequest request) {
        return new Session(request, waitEngine).run();
    }

    /**
     * One crawl's mutable state and orchestration loop.
     *
     * <p>Every method on this class - frontier expansion, claiming, navigation, and result assembly
     * alike - runs on the single thread that calls {@link #run()}. There is no worker pool and no
     * {@code ThreadLocal}: {@link io.webagent4j.browser.IBrowser#newPage()} and every {@link
     * io.webagent4j.browser.IPage} operation this engine performs are called from that one thread
     * only, for the whole lifetime of the crawl. This is a deliberate architecture decision, not an
     * oversight - see {@code docs/browser-crawler.md#concurrency-model} for the full rationale: a
     * shared {@code IBrowser} session is not documented as thread-safe (neither is the {@code
     * IPage} it creates), so offloading navigation to worker threads - the original Phase 0.7
     * design - silently corrupted crawl results under real Playwright (a discovered page went
     * missing under concurrent navigation; see the regression test named after that failure).
     */
    private static final class Session {

        private final BrowserCrawlRequest request;
        private final WaitEngine waitEngine;
        private final PageStabilityWaiter stabilityWaiter;
        private final IUrlNormalizer normalizer;
        private final ClaimGate claimGate;
        private final BrowserCrawlFrontier frontier = new BrowserCrawlFrontier();
        private long sequenceCounter = 0;
        private IPage page;

        private final List<BrowserCrawledPage> pages = new ArrayList<>();
        private final List<BrowserCrawlFailure> failures = new ArrayList<>();
        private final List<DiscoveredLink> rejectedUrls = new ArrayList<>();
        private int discoveredUrls = 0;
        private int duplicateUrls = 0;
        private int outOfScopeUrls = 0;
        private int cancelledTasks = 0;
        private int maxDepthReached = 0;
        private boolean maxPagesHit = false;
        private boolean fatalFailureHit = false;
        private boolean stopRequested = false;

        Session(BrowserCrawlRequest request, WaitEngine waitEngine) {
            this.request = request;
            this.waitEngine = waitEngine;
            this.stabilityWaiter = new PageStabilityWaiter();
            this.normalizer = new BrowserUrlNormalizer(request.queryParameterPolicy());
            this.claimGate = new ClaimGate(request.maxPages());
        }

        BrowserCrawlResult run() {
            try {
                for (URI seed : request.seeds()) {
                    claimAndEnqueue(seed, 0, Optional.empty());
                }
                while (!stopRequested && !frontier.isEmpty()) {
                    BrowserCrawlTask task = frontier.poll().orElseThrow();
                    commit(task, execute(task));
                }
            } finally {
                if (page != null) {
                    closeQuietly(page);
                }
                if (request.closeBrowserOnCompletion()) {
                    closeQuietly(request.browser());
                }
            }
            return new BrowserCrawlResult(
                    pages,
                    failures,
                    new BrowserCrawlStatistics(
                            discoveredUrls,
                            claimGate.claimedCount(),
                            pages.size(),
                            failures.size(),
                            duplicateUrls,
                            outOfScopeUrls,
                            cancelledTasks,
                            maxDepthReached),
                    rejectedUrls,
                    terminationReason());
        }

        private BrowserCrawlTerminationReason terminationReason() {
            if (request.cancellationToken().isCancelled()) {
                return BrowserCrawlTerminationReason.CANCELLED;
            }
            if (fatalFailureHit) {
                return BrowserCrawlTerminationReason.FAIL_FAST;
            }
            if (maxPagesHit) {
                return BrowserCrawlTerminationReason.MAX_PAGES_REACHED;
            }
            return BrowserCrawlTerminationReason.COMPLETED;
        }

        // ---- discovery-time decisions (single-threaded: called only from run()/commit()) ----

        /**
         * Centralizes the "no new navigation identity may be claimed once cancellation has been
         * observed" invariant - both {@link #claimAndEnqueue} (seeds) and {@link
         * #processDiscoveredLink} (children discovered from a committed page) check this before
         * doing anything else, so neither path can accidentally claim past a cancellation any
         * future change routes through. Already-claimed, in-flight navigations are still allowed to
         * finish (see {@link CancellationToken}) - this only ever blocks a claim that has not
         * happened yet.
         */
        private boolean cancellationBlocksNewClaims() {
            return request.cancellationToken().isCancelled();
        }

        private void claimAndEnqueue(URI candidate, int depth, Optional<URI> discoveredFrom) {
            if (cancellationBlocksNewClaims()) {
                return;
            }
            CrawlDecision scopeDecision = ScopeEvaluator.evaluate(candidate, request);
            if (!scopeDecision.allowed()) {
                outOfScopeUrls++;
                rejectedUrls.add(rejectedLink(candidate, scopeDecision));
                return;
            }
            if (depth > request.maxDepth()) {
                outOfScopeUrls++;
                CrawlDecision depthDecision =
                        CrawlDecision.reject(CrawlDecisionType.REJECT_DEPTH, "max depth exceeded");
                rejectedUrls.add(rejectedLink(candidate, depthDecision));
                return;
            }
            URI normalized = normalizer.normalize(candidate);
            ClaimGate.Outcome outcome = claimGate.tryClaim(normalized);
            switch (outcome) {
                case ALREADY_CLAIMED -> {
                    duplicateUrls++;
                    rejectedUrls.add(
                            new DiscoveredLink(
                                    candidate,
                                    Optional.of(normalized),
                                    candidate.toString(),
                                    Optional.empty(),
                                    SEED_LINK_KIND,
                                    false,
                                    Optional.of(
                                            CrawlDecision.reject(
                                                    CrawlDecisionType.REJECT_DUPLICATE,
                                                    "already claimed")),
                                    0));
                }
                case LIMIT_REACHED -> {
                    maxPagesHit = true;
                    rejectedUrls.add(
                            new DiscoveredLink(
                                    candidate,
                                    Optional.of(normalized),
                                    candidate.toString(),
                                    Optional.empty(),
                                    SEED_LINK_KIND,
                                    false,
                                    Optional.of(
                                            CrawlDecision.reject(
                                                    CrawlDecisionType.REJECT_MAX_PAGES,
                                                    "maxPages reached")),
                                    0));
                }
                case CLAIMED -> {
                    maxDepthReached = Math.max(maxDepthReached, depth);
                    frontier.enqueue(
                            new BrowserCrawlTask(
                                    sequenceCounter++,
                                    normalized,
                                    depth,
                                    normalized,
                                    discoveredFrom));
                }
            }
        }

        /**
         * A rejected seed never originated from any HTML element, so no {@link LinkKind} value
         * genuinely describes it - {@code DiscoveredLink#kind()} is non-nullable, though, and
         * {@code HttpCrawler} sidesteps this entirely by never representing a rejected seed as a
         * {@code DiscoveredLink} in the first place (see its {@code enqueueSeed}), so there is no
         * cross-engine convention to match either way. {@link LinkKind#ANCHOR} is used here as the
         * closest existing value - a deliberate, documented convention, not an inferred provenance
         * claim that a seed came from an {@code <a>} element.
         */
        private static final LinkKind SEED_LINK_KIND = LinkKind.ANCHOR;

        private DiscoveredLink rejectedLink(URI candidate, CrawlDecision decision) {
            return new DiscoveredLink(
                    candidate,
                    Optional.empty(),
                    candidate.toString(),
                    Optional.empty(),
                    SEED_LINK_KIND,
                    false,
                    Optional.of(decision),
                    0);
        }

        // ---- navigation (always on the single thread that called run()) ----

        private IPage page() {
            if (page == null) {
                page = request.browser().newPage();
            }
            return page;
        }

        private ITaskOutcome execute(BrowserCrawlTask task) {
            if (request.cancellationToken().isCancelled()) {
                return new NavigationFailure(
                        BrowserCrawlFailureType.CANCELLED,
                        "cancelled before navigation began",
                        Optional.empty());
            }
            IPage page = page();
            WaitBudget budget = WaitBudget.start(request.navigationTimeout(), waitEngine.clock());
            // budget.remaining().toMillis() < 1, not budget.expired(): IPage#navigate(String,
            // Duration) now rejects a positive-but-sub-millisecond timeout with
            // IllegalArgumentException (see IPage's class-level "Timeout precision" note) rather
            // than silently flooring it, so a remaining budget under 1ms must be treated as
            // already-expired here, before ever reaching that validation, exactly like
            // PageStabilityWaiter does for the stability leg.
            if (budget.remaining().toMillis() < 1) {
                return new NavigationFailure(
                        BrowserCrawlFailureType.NAVIGATION_TIMEOUT,
                        "navigationTimeout budget already elapsed before navigation began",
                        Optional.empty());
            }
            try {
                // The remaining budget, not any backend default, is the authoritative bound - see
                // docs/browser-crawler.md#navigation-timeout. A backend that cannot honor a
                // caller-supplied timeout fails explicitly (IPage#navigate(String, Duration)'s
                // default throws UnsupportedOperationException) rather than silently ignoring it.
                page.navigate(task.url().toString(), budget.remaining());
            } catch (NavigationTimeoutException e) {
                // Typed, backend-neutral timeout signal - see NavigationTimeoutException. No
                // budget-expiry inference and no backend-specific message parsing are needed or
                // used
                // for this classification.
                return new NavigationFailure(
                        BrowserCrawlFailureType.NAVIGATION_TIMEOUT, e.getMessage(), Optional.of(e));
            } catch (RuntimeException e) {
                return new NavigationFailure(
                        classifyNavigationException(e), e.getMessage(), Optional.of(e));
            }
            try {
                // ConditionTimeoutException carries its own honest cause (present when the backend
                // genuinely observed the condition never settle, absent when the shared budget was
                // already exhausted before a backend call was even attempted - see
                // PageStabilityWaiter#awaitStable) - preserved here, never re-inferred from
                // WaitBudget timing or a message.
                stabilityWaiter.awaitStable(page, budget, request.stabilityWindow());
            } catch (ConditionTimeoutException e) {
                return new NavigationFailure(
                        BrowserCrawlFailureType.PAGE_STABILITY_TIMEOUT,
                        e.getMessage(),
                        Optional.of(e));
            } catch (RuntimeException e) {
                // An unsupported backend (IPage#waitForCondition's default) or another genuinely
                // unexpected backend failure still surfaces as a RuntimeException here rather than
                // crashing the whole crawl.
                return new NavigationFailure(
                        BrowserCrawlFailureType.BROWSER_BACKEND_FAILURE,
                        e.getMessage(),
                        Optional.of(e));
            }
            try {
                URI finalUrl = URI.create(page.url());
                CrawlDecision finalScope = ScopeEvaluator.evaluate(finalUrl, request);
                if (!finalScope.allowed()) {
                    return new NavigationFailure(
                            BrowserCrawlFailureType.OUT_OF_SCOPE_REDIRECT,
                            "final URL left scope: " + finalScope.reason(),
                            Optional.empty());
                }
                Observation observation =
                        page.observe(ObservationOptions.builder().maxElements(2000).build());
                if (observation.statistics().truncated()) {
                    // A truncated observation may be missing links past the retained boundary - it
                    // must never be recorded as a complete, successful discovery (see
                    // docs/browser-crawler.md#observation-truncation). The bound itself is not
                    // relaxed: raising maxElements only moves the same problem to a larger page.
                    return new NavigationFailure(
                            BrowserCrawlFailureType.OBSERVATION_TRUNCATED,
                            describeTruncation(observation.statistics()),
                            Optional.empty());
                }
                List<RawLink> rawLinks = LinkDiscoverer.discover(observation, finalUrl);
                String title = page.title();
                // budget.elapsed() here is deliberately total navigation+stability elapsed time -
                // budget.start() precedes navigate() - not stability-only. See
                // BrowserCrawledPage#timeToStability.
                return new NavigationSuccess(
                        finalUrl,
                        (title == null || title.isBlank()) ? Optional.empty() : Optional.of(title),
                        rawLinks,
                        budget.elapsed());
            } catch (RuntimeException e) {
                return new NavigationFailure(
                        BrowserCrawlFailureType.BROWSER_BACKEND_FAILURE,
                        e.getMessage(),
                        Optional.of(e));
            }
        }

        /**
         * Classifies a navigation exception that is not the typed {@link
         * NavigationTimeoutException} (handled separately, deterministically, in {@link #execute}).
         * {@code PAGE_CLOSED} remains message-based - {@link IPage} exposes no deterministic "was
         * this page closed" signal - and is the one remaining, deliberately narrow exception to
         * this engine's no-message-parsing rule for failure classification.
         */
        private static BrowserCrawlFailureType classifyNavigationException(RuntimeException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("closed")) {
                return BrowserCrawlFailureType.PAGE_CLOSED;
            }
            return BrowserCrawlFailureType.NAVIGATION_FAILED;
        }

        /**
         * Bounded, backend-neutral diagnostics for an {@link ObservationStatistics#truncated()}.
         */
        private static String describeTruncation(ObservationStatistics statistics) {
            StringBuilder message = new StringBuilder("observation truncated: ");
            List<ObservationTruncation> truncations = statistics.truncations();
            for (int i = 0; i < truncations.size(); i++) {
                if (i > 0) {
                    message.append(", ");
                }
                ObservationTruncation truncation = truncations.get(i);
                message.append(truncation.type())
                        .append(" retained ")
                        .append(truncation.retainedCount())
                        .append('/')
                        .append(truncation.originalCount());
            }
            return message.toString();
        }

        // ---- single-threaded commit: appends results in strict frontier order ----

        private void commit(BrowserCrawlTask task, ITaskOutcome outcome) {
            if (outcome instanceof NavigationSuccess success) {
                List<DiscoveredLink> links = new ArrayList<>(success.rawLinks().size());
                for (RawLink raw : success.rawLinks()) {
                    discoveredUrls++;
                    links.add(processDiscoveredLink(raw, task));
                }
                pages.add(
                        new BrowserCrawledPage(
                                task.url(),
                                success.finalUrl(),
                                task.depth(),
                                task.discoveredFrom(),
                                success.title(),
                                links,
                                (int) task.sequence(),
                                success.timeToStability()));
            } else if (outcome instanceof NavigationFailure failure) {
                if (failure.type() == BrowserCrawlFailureType.CANCELLED) {
                    cancelledTasks++;
                } else if (request.failFast()) {
                    fatalFailureHit = true;
                    stopRequested = true;
                }
                failures.add(
                        new BrowserCrawlFailure(
                                task.url(),
                                task.depth(),
                                failure.type(),
                                failure.message() == null ? "" : failure.message(),
                                failure.cause(),
                                task.discoveredFrom(),
                                (int) task.sequence()));
            }
        }

        private DiscoveredLink processDiscoveredLink(RawLink raw, BrowserCrawlTask parent) {
            if (cancellationBlocksNewClaims()) {
                CrawlDecision cancelled =
                        CrawlDecision.reject(
                                CrawlDecisionType.REJECT_CANCELLED, "crawl was cancelled");
                DiscoveredLink rejected = toDiscoveredLink(raw, Optional.empty(), false, cancelled);
                rejectedUrls.add(rejected);
                return rejected;
            }
            CrawlDecision scopeDecision = ScopeEvaluator.evaluate(raw.resolvedUrl(), request);
            if (!scopeDecision.allowed()) {
                outOfScopeUrls++;
                DiscoveredLink rejected =
                        toDiscoveredLink(raw, Optional.empty(), false, scopeDecision);
                rejectedUrls.add(rejected);
                return rejected;
            }
            int childDepth = parent.depth() + 1;
            if (childDepth > request.maxDepth()) {
                outOfScopeUrls++;
                CrawlDecision depthDecision =
                        CrawlDecision.reject(CrawlDecisionType.REJECT_DEPTH, "max depth exceeded");
                DiscoveredLink rejected =
                        toDiscoveredLink(raw, Optional.empty(), false, depthDecision);
                rejectedUrls.add(rejected);
                return rejected;
            }
            URI normalized = normalizer.normalize(raw.resolvedUrl());
            ClaimGate.Outcome claim = claimGate.tryClaim(normalized);
            if (claim == ClaimGate.Outcome.ALREADY_CLAIMED) {
                duplicateUrls++;
                CrawlDecision dup =
                        CrawlDecision.reject(CrawlDecisionType.REJECT_DUPLICATE, "already claimed");
                DiscoveredLink rejected =
                        toDiscoveredLink(raw, Optional.of(normalized), false, dup);
                rejectedUrls.add(rejected);
                return rejected;
            }
            if (claim == ClaimGate.Outcome.LIMIT_REACHED) {
                maxPagesHit = true;
                CrawlDecision limit =
                        CrawlDecision.reject(
                                CrawlDecisionType.REJECT_MAX_PAGES, "maxPages reached");
                DiscoveredLink rejected =
                        toDiscoveredLink(raw, Optional.of(normalized), false, limit);
                rejectedUrls.add(rejected);
                return rejected;
            }
            maxDepthReached = Math.max(maxDepthReached, childDepth);
            frontier.enqueue(
                    new BrowserCrawlTask(
                            sequenceCounter++,
                            normalized,
                            childDepth,
                            parent.seedOrigin(),
                            Optional.of(parent.url())));
            return toDiscoveredLink(raw, Optional.of(normalized), true, null);
        }

        private DiscoveredLink toDiscoveredLink(
                RawLink raw, Optional<URI> normalized, boolean allowed, CrawlDecision rejection) {
            return new DiscoveredLink(
                    raw.resolvedUrl(),
                    normalized,
                    raw.rawHref(),
                    raw.anchorText(),
                    raw.kind(),
                    allowed,
                    Optional.ofNullable(rejection),
                    raw.documentOrder());
        }

        private static void closeQuietly(AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // best-effort cleanup; a failure to close does not change the crawl's own outcome
            }
        }
    }

    private sealed interface ITaskOutcome permits NavigationSuccess, NavigationFailure {}

    private record NavigationSuccess(
            URI finalUrl, Optional<String> title, List<RawLink> rawLinks, Duration timeToStability)
            implements ITaskOutcome {}

    private record NavigationFailure(
            BrowserCrawlFailureType type, String message, Optional<Throwable> cause)
            implements ITaskOutcome {}
}
