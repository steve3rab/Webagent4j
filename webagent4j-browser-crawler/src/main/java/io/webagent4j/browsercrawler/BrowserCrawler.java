package io.webagent4j.browsercrawler;

import io.webagent4j.browser.IPage;
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
import io.webagent4j.wait.WaitBudget;
import io.webagent4j.wait.WaitEngine;
import io.webagent4j.wait.WaitResult;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The deterministic, bounded-concurrency browser crawler engine - the only implementation of {@link
 * IBrowserCrawler}, mirroring how {@code HttpCrawler} is the only implementation of {@code
 * ICrawler}.
 *
 * <p>Each {@link #crawl(BrowserCrawlRequest)} call constructs a private, per-call {@code Session}
 * holding all mutable state, so one {@code BrowserCrawler} instance is stateless and safe to reuse
 * across calls - the same pattern {@code HttpCrawler} uses. See {@code docs/browser-crawler.md} for
 * the full navigation/stability/concurrency/failure pipeline.
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
     * One crawl's mutable state and orchestration loop. Frontier expansion, claiming, and result
     * assembly all happen on the single coordinator thread that calls {@link #run()} - only page
     * navigation is offloaded to worker threads. See {@code
     * docs/browser-crawler.md#bounded-concurrency} for why this makes concurrent completion order
     * irrelevant to the deterministic result order.
     */
    private static final class Session {

        private final BrowserCrawlRequest request;
        private final WaitEngine waitEngine;
        private final PageStabilityWaiter stabilityWaiter;
        private final IUrlNormalizer normalizer;
        private final ClaimGate claimGate;
        private final BrowserCrawlFrontier frontier = new BrowserCrawlFrontier();
        private final AtomicLong sequenceCounter = new AtomicLong();
        private final ExecutorService executor;
        private final ThreadLocal<IPage> workerPage;
        private final Set<IPage> ownedPages = ConcurrentHashMap.newKeySet();

        private final List<BrowserCrawledPage> pages = new ArrayList<>();
        private final List<BrowserCrawlFailure> failures = new ArrayList<>();
        private final List<DiscoveredLink> rejectedUrls = new ArrayList<>();
        private int discoveredUrls = 0;
        private int duplicateUrls = 0;
        private int outOfScopeUrls = 0;
        private int cancelledTasks = 0;
        private int maxDepthReached = 0;
        private volatile boolean maxPagesHit = false;
        private boolean fatalFailureHit = false;
        private boolean stopRequested = false;

        Session(BrowserCrawlRequest request, WaitEngine waitEngine) {
            this.request = request;
            this.waitEngine = waitEngine;
            this.stabilityWaiter = new PageStabilityWaiter(waitEngine);
            this.normalizer = new BrowserUrlNormalizer(request.queryParameterPolicy());
            this.claimGate = new ClaimGate(request.maxPages());
            this.executor = Executors.newFixedThreadPool(request.maxConcurrency());
            this.workerPage =
                    ThreadLocal.withInitial(
                            () -> {
                                IPage page = request.browser().newPage();
                                ownedPages.add(page);
                                return page;
                            });
        }

        BrowserCrawlResult run() {
            try {
                for (URI seed : request.seeds()) {
                    claimAndEnqueue(seed, 0, Optional.empty());
                }
                Map<Long, BrowserCrawlTask> submitted = new LinkedHashMap<>();
                Map<Long, Future<ITaskOutcome>> inFlight = new LinkedHashMap<>();
                while (!frontier.isEmpty() || !inFlight.isEmpty()) {
                    if (!stopRequested) {
                        while (inFlight.size() < request.maxConcurrency() && !frontier.isEmpty()) {
                            BrowserCrawlTask task = frontier.poll().orElseThrow();
                            submitted.put(task.sequence(), task);
                            inFlight.put(task.sequence(), executor.submit(() -> execute(task)));
                        }
                    }
                    if (inFlight.isEmpty()) {
                        break;
                    }
                    long nextSequence = inFlight.keySet().iterator().next();
                    Future<ITaskOutcome> future = inFlight.remove(nextSequence);
                    BrowserCrawlTask task = submitted.remove(nextSequence);
                    commit(task, awaitOutcome(future));
                }
            } finally {
                executor.shutdown();
                for (IPage page : ownedPages) {
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

        private ITaskOutcome awaitOutcome(Future<ITaskOutcome> future) {
            try {
                return future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new NavigationFailure(
                        BrowserCrawlFailureType.BROWSER_BACKEND_FAILURE,
                        "crawl coordinator interrupted",
                        Optional.of(e));
            } catch (ExecutionException e) {
                return new NavigationFailure(
                        BrowserCrawlFailureType.BROWSER_BACKEND_FAILURE,
                        "unexpected worker failure: " + e.getCause(),
                        Optional.ofNullable(e.getCause()));
            }
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

        private void claimAndEnqueue(URI candidate, int depth, Optional<URI> discoveredFrom) {
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
                                    LinkKind.ANCHOR,
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
                                    LinkKind.ANCHOR,
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
                                    sequenceCounter.getAndIncrement(),
                                    normalized,
                                    depth,
                                    normalized,
                                    discoveredFrom));
                }
            }
        }

        private DiscoveredLink rejectedLink(URI candidate, CrawlDecision decision) {
            return new DiscoveredLink(
                    candidate,
                    Optional.empty(),
                    candidate.toString(),
                    Optional.empty(),
                    LinkKind.ANCHOR,
                    false,
                    Optional.of(decision),
                    0);
        }

        // ---- worker-thread navigation (may run concurrently across tasks) ----

        private ITaskOutcome execute(BrowserCrawlTask task) {
            if (request.cancellationToken().isCancelled()) {
                return new NavigationFailure(
                        BrowserCrawlFailureType.CANCELLED,
                        "cancelled before navigation began",
                        Optional.empty());
            }
            IPage page = workerPage.get();
            WaitBudget budget = WaitBudget.start(request.navigationTimeout(), waitEngine.clock());
            try {
                page.navigate(task.url().toString());
            } catch (RuntimeException e) {
                return new NavigationFailure(
                        classifyNavigationException(e, budget), e.getMessage(), Optional.of(e));
            }
            WaitResult<String> stability =
                    stabilityWaiter.awaitStable(page, budget, request.stabilityWindow());
            if (!stability.success()) {
                return new NavigationFailure(
                        BrowserCrawlFailureType.PAGE_STABILITY_TIMEOUT,
                        "page did not stabilize within " + request.navigationTimeout(),
                        Optional.empty());
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
                List<RawLink> rawLinks = LinkDiscoverer.discover(observation, finalUrl);
                String title = page.title();
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

        private static BrowserCrawlFailureType classifyNavigationException(
                RuntimeException e, WaitBudget budget) {
            if (budget.expired()) {
                return BrowserCrawlFailureType.NAVIGATION_TIMEOUT;
            }
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("closed")) {
                return BrowserCrawlFailureType.PAGE_CLOSED;
            }
            return BrowserCrawlFailureType.NAVIGATION_FAILED;
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
                                success.stabilityElapsed()));
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
                            sequenceCounter.getAndIncrement(),
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
                    LinkKind.ANCHOR,
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
            URI finalUrl, Optional<String> title, List<RawLink> rawLinks, Duration stabilityElapsed)
            implements ITaskOutcome {}

    private record NavigationFailure(
            BrowserCrawlFailureType type, String message, Optional<Throwable> cause)
            implements ITaskOutcome {}
}
