package io.webagent4j.locator;

import io.webagent4j.locator.LocatorDiagnostics.Ambiguity;
import io.webagent4j.locator.LocatorDiagnostics.BudgetLimit;
import io.webagent4j.locator.LocatorDiagnostics.RejectionReason;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.locator.internal.LocatorCandidateOrder;
import io.webagent4j.locator.internal.LocatorDiagnosticsAccumulator;
import io.webagent4j.locator.internal.LocatorResolutionWaiter;
import io.webagent4j.wait.IMonotonicClock;
import io.webagent4j.wait.WaitBudget;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default deterministic locator engine orchestrating exact-first discovery, hard constraints,
 * candidate deduplication, evidence aggregation, stable ranking and bounded fuzzy fallback.
 */
public final class LocatorEngine implements ILocatorEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocatorEngine.class);
    private static final Comparator<LocatorCandidate> CANDIDATE_ORDER =
            LocatorCandidateOrder.comparator();
    private static final IMonotonicClock CLOCK = IMonotonicClock.systemClock();

    private final ILocatorStrategyRegistry registry;
    private final LocatorPlanFactory planFactory;
    private final LocatorFilter filter;
    private final LocatorScorer scorer;
    private final LocatorDiagnosticsRenderer renderer;
    private final ILocatorEventListener eventListener;
    private final LocatorResolutionWaiter waiter;

    /** Creates an engine with all standard deterministic strategies and no-op event delivery. */
    public LocatorEngine() {
        this(
                LocatorStrategyRegistry.defaults(),
                new LocatorPlanFactory(),
                new LocatorFilter(),
                new LocatorScorer(),
                new LocatorDiagnosticsRenderer(),
                ILocatorEventListener.noOp(),
                new LocatorResolutionWaiter());
    }

    /**
     * Creates an engine with explicit composable responsibilities and a custom strategy registry.
     */
    public LocatorEngine(
            ILocatorStrategyRegistry registry,
            LocatorPlanFactory planFactory,
            LocatorFilter filter,
            LocatorScorer scorer,
            LocatorDiagnosticsRenderer renderer) {
        this(
                registry,
                planFactory,
                filter,
                scorer,
                renderer,
                ILocatorEventListener.noOp(),
                new LocatorResolutionWaiter());
    }

    /** Creates an observable engine without introducing a global mutable event bus. */
    public LocatorEngine(
            ILocatorStrategyRegistry registry,
            LocatorPlanFactory planFactory,
            LocatorFilter filter,
            LocatorScorer scorer,
            LocatorDiagnosticsRenderer renderer,
            ILocatorEventListener eventListener) {
        this(
                registry,
                planFactory,
                filter,
                scorer,
                renderer,
                eventListener,
                new LocatorResolutionWaiter());
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private LocatorEngine(
            ILocatorStrategyRegistry registry,
            LocatorPlanFactory planFactory,
            LocatorFilter filter,
            LocatorScorer scorer,
            LocatorDiagnosticsRenderer renderer,
            ILocatorEventListener eventListener,
            LocatorResolutionWaiter waiter) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.planFactory = Objects.requireNonNull(planFactory, "planFactory");
        this.filter = Objects.requireNonNull(filter, "filter");
        this.scorer = Objects.requireNonNull(scorer, "scorer");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.eventListener = Objects.requireNonNull(eventListener, "eventListener");
        this.waiter = Objects.requireNonNull(waiter, "waiter");
    }

    @Override
    public LocatorResult locate(LocatorContext context, LocatorDefinition definition) {
        Resolution resolution = resolve(context, definition, true);
        if (resolution.candidates().isEmpty()) {
            throw notFound(context, resolution);
        }
        LocatorCandidate selected = resolution.candidates().get(0);
        LocatorDiagnostics diagnostics =
                resolution
                        .diagnostics()
                        .snapshot(resolution.candidates(), Optional.of(selected), Optional.empty());
        completed(selected, diagnostics);
        return result(definition, selected, resolution.candidates(), diagnostics);
    }

    @Override
    public LocatorResult locateSingle(LocatorContext context, LocatorDefinition definition) {
        Resolution resolution = resolve(context, definition, true);
        if (resolution.candidates().isEmpty()) {
            throw notFound(context, resolution);
        }
        LocatorCandidate selected = resolution.candidates().get(0);
        if (ambiguous(resolution.candidates(), context.config().ambiguityMargin())) {
            LocatorCandidate second = resolution.candidates().get(1);
            Ambiguity ambiguity =
                    new Ambiguity(
                            selected.identity(),
                            selected.score(),
                            second.identity(),
                            second.score(),
                            context.config().ambiguityMargin());
            LocatorDiagnostics diagnostics =
                    resolution
                            .diagnostics()
                            .snapshot(
                                    resolution.candidates(),
                                    Optional.empty(),
                                    Optional.of(ambiguity));
            failed("ambiguous", diagnostics);
            throw new AmbiguousLocatorException(
                    "Locator is ambiguous"
                            + System.lineSeparator()
                            + renderer.render(diagnostics, resolution.candidates()),
                    diagnostics);
        }
        LocatorDiagnostics diagnostics =
                resolution
                        .diagnostics()
                        .snapshot(resolution.candidates(), Optional.of(selected), Optional.empty());
        completed(selected, diagnostics);
        return result(definition, selected, resolution.candidates(), diagnostics);
    }

    @Override
    public List<LocatorCandidate> locateAll(LocatorContext context, LocatorDefinition definition) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(definition, "definition");
        boolean waitForCandidates =
                definition.waitUntilVisible() || definition.stability().isPresent();
        Resolution resolution = resolve(context, definition, waitForCandidates);
        Optional<LocatorCandidate> selected = resolution.candidates().stream().findFirst();
        LocatorDiagnostics diagnostics =
                resolution
                        .diagnostics()
                        .snapshot(resolution.candidates(), selected, Optional.empty());
        selected.ifPresent(candidate -> completed(candidate, diagnostics));
        return resolution.candidates();
    }

    private Resolution resolve(
            LocatorContext context, LocatorDefinition definition, boolean waitForCandidates) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(definition, "definition");
        LocatorDiagnosticsAccumulator diagnostics =
                new LocatorDiagnosticsAccumulator(definition, context.config(), context.scope());
        eventListener.onEvent(
                new ILocatorEvent.ResolutionStarted(
                        Instant.now(), definition, context.config().resolutionPolicy()));
        Duration timeout = context.timeoutFor(definition);
        WaitBudget budget = WaitBudget.start(timeout, CLOCK);
        String stableIdentity = null;
        long stableSince = 0L;
        List<LocatorCandidate> latest = List.of();
        do {
            Duration remaining = atLeastOneNano(budget.remaining());
            latest = searchOnce(context, definition, remaining, diagnostics);
            if (!latest.isEmpty()) {
                if (definition.stability().isEmpty()) {
                    if (!awaitExactCandidate(definition, waitForCandidates, latest)) {
                        return new Resolution(latest, diagnostics);
                    }
                } else {
                    String identities =
                            String.join(
                                    "|", latest.stream().map(LocatorCandidate::identity).toList());
                    if (!identities.equals(stableIdentity)) {
                        stableIdentity = identities;
                        stableSince = CLOCK.nanoTime();
                    }
                    if (CLOCK.nanoTime() - stableSince
                            >= definition.stability().orElseThrow().toNanos()) {
                        return new Resolution(latest, diagnostics);
                    }
                }
            } else {
                stableIdentity = null;
                stableSince = 0L;
                if (!waitForCandidates) {
                    return new Resolution(List.of(), diagnostics);
                }
            }
            if (budget.expired()) {
                diagnostics.limit(BudgetLimit.TIMEOUT);
                break;
            }
            waiter.awaitNextPoll(context.config().pollingInterval(), budget.remaining());
        } while (true);
        LOGGER.debug("Locator timed out after {} ms", timeout.toMillis());
        return new Resolution(latest, diagnostics);
    }

    private List<LocatorCandidate> searchOnce(
            LocatorContext context,
            LocatorDefinition definition,
            Duration timeout,
            LocatorDiagnosticsAccumulator diagnostics) {
        List<ExecutionUnit> units = executionUnits(definition);
        Map<String, LocatorCandidate> aggregated = new LinkedHashMap<>();
        int executed = 0;
        boolean deterministicCandidateFound = false;
        boolean deterministicTextMatchRejected = false;
        LOGGER.debug(
                "Locator resolution policy={} role={} strategies={} scopeDepth={}",
                context.config().resolutionPolicy(),
                definition.role().map(Enum::name).orElse("ANY"),
                units.size(),
                context.scope().path().size());
        for (int index = 0; index < units.size(); index++) {
            ExecutionUnit unit = units.get(index);
            LocatorStrategyType type = unit.strategy().type();
            if (executed >= context.config().resolutionBudget().maxStrategies()) {
                diagnostics.limit(BudgetLimit.STRATEGIES);
                skipRemaining(units, index, diagnostics, "strategy budget reached");
                break;
            }
            if (unit.strategy().phase() == LocatorStrategyPhase.FALLBACK
                    && (deterministicCandidateFound || deterministicTextMatchRejected)) {
                diagnostics.skipped(type, "deterministic candidates available");
                continue;
            }
            if (unit.strategy().phase() == LocatorStrategyPhase.FALLBACK
                    && !context.config().allowFuzzyMatching()) {
                diagnostics.skipped(type, "disabled by STRICT policy");
                continue;
            }
            if (type != LocatorStrategyType.CUSTOM
                    && !context.backend().capabilities().supports(type)) {
                diagnostics.skipped(type, "unsupported by backend");
                continue;
            }
            if (aggregated.size() >= context.config().resolutionBudget().maxCandidates()) {
                diagnostics.limit(BudgetLimit.CANDIDATES);
                skipRemaining(units, index, diagnostics, "candidate budget reached");
                break;
            }
            int candidateLimit = candidateLimit(context.config(), unit.strategy().phase());
            Instant strategyStarted = Instant.now();
            LocatorBackendSearchResult discovered =
                    unit.strategy()
                            .discover(
                                    definition,
                                    unit.step(),
                                    context,
                                    timeout,
                                    candidateLimit,
                                    sorted(aggregated));
            executed++;
            Acceptance acceptance =
                    acceptCandidates(
                            definition, context, unit, discovered, aggregated, diagnostics);
            int accepted = acceptance.accepted();
            deterministicTextMatchRejected =
                    deterministicTextMatchRejected
                            || (unit.strategy().phase() == LocatorStrategyPhase.DETERMINISTIC
                                    && unit.step().query().text().isPresent()
                                    && acceptance.hardConstraintRejected());
            Duration strategyDuration = Duration.between(strategyStarted, Instant.now());
            diagnostics.executed(type, strategyDuration, discovered, accepted);
            if (discovered.truncated()
                    && unit.strategy().phase() == LocatorStrategyPhase.FALLBACK) {
                diagnostics.limit(BudgetLimit.FUZZY_CANDIDATES);
            }
            eventListener.onEvent(
                    new ILocatorEvent.StrategyExecuted(
                            Instant.now(), type, discovered.discoveredCount(), strategyDuration));
            List<LocatorCandidate> ranked = sorted(aggregated);
            deterministicCandidateFound =
                    deterministicCandidateFound
                            || (unit.strategy().phase() == LocatorStrategyPhase.DETERMINISTIC
                                    && !ranked.isEmpty());
            LOGGER.debug(
                    "Locator strategy={} discovered={} accepted={} deduplicatedTotal={}",
                    type,
                    discovered.discoveredCount(),
                    accepted,
                    ranked.size());
            if (earlyStop(ranked, unit.strategy().phase(), context.config())) {
                skipRemaining(units, index + 1, diagnostics, "exact unique early stop");
                LOGGER.debug(
                        "Locator early stop strategy={} score={}", type, ranked.get(0).score());
                return ranked;
            }
        }
        List<LocatorCandidate> ranked = sorted(aggregated);
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace(
                    "Locator candidate summary count={} exact={} fuzzy={}",
                    ranked.size(),
                    ranked.stream().filter(LocatorCandidate::exactMatch).count(),
                    ranked.stream()
                            .filter(
                                    candidate ->
                                            candidate.hasEvidence(LocatorStrategyType.FUZZY_TEXT))
                            .count());
        }
        return ranked;
    }

    private Acceptance acceptCandidates(
            LocatorDefinition definition,
            LocatorContext context,
            ExecutionUnit unit,
            LocatorBackendSearchResult discovered,
            Map<String, LocatorCandidate> aggregated,
            LocatorDiagnosticsAccumulator diagnostics) {
        int accepted = 0;
        boolean hardConstraintRejected = false;
        for (LocatorBackendCandidate backendCandidate : discovered.candidates()) {
            if (!aggregated.containsKey(backendCandidate.identity())
                    && aggregated.size() >= context.config().resolutionBudget().maxCandidates()) {
                diagnostics.limit(BudgetLimit.CANDIDATES);
                break;
            }
            Optional<RejectionReason> rejection;
            try {
                rejection =
                        filter.rejectionReason(
                                definition,
                                backendCandidate.element(),
                                context.config().testIdAttribute());
            } catch (RuntimeException detached) {
                rejection = Optional.of(RejectionReason.OUTSIDE_SCOPE);
            }
            if (rejection.isPresent()) {
                hardConstraintRejected = true;
                diagnostics.rejected(
                        backendCandidate.identity(),
                        unit.strategy().type(),
                        rejection.orElseThrow());
                continue;
            }
            LocatorScorer.ScoreDecision decision =
                    scorer.score(
                            definition,
                            unit.step(),
                            backendCandidate,
                            context.config().scoring(),
                            context.config().fuzzyThreshold(),
                            context.config().locale());
            if (decision.candidate().isEmpty()) {
                diagnostics.rejected(
                        backendCandidate.identity(),
                        unit.strategy().type(),
                        RejectionReason.BELOW_THRESHOLD);
                continue;
            }
            LocatorCandidate candidate = decision.candidate().orElseThrow();
            LocatorCandidate previous = aggregated.putIfAbsent(candidate.identity(), candidate);
            if (previous != null) {
                diagnostics.deduplicated(candidate.identity(), unit.strategy().type());
                candidate = merge(previous, candidate);
                aggregated.put(candidate.identity(), candidate);
            }
            eventListener.onEvent(
                    new ILocatorEvent.CandidateFound(
                            Instant.now(),
                            candidate.identity(),
                            unit.strategy().type(),
                            candidate.score()));
            accepted++;
        }
        return new Acceptance(accepted, hardConstraintRejected);
    }

    private List<ExecutionUnit> executionUnits(LocatorDefinition definition) {
        LocatorPlan plan = planFactory.create(definition);
        List<ExecutionUnit> deterministic = new ArrayList<>();
        List<ExecutionUnit> fallback = new ArrayList<>();
        for (LocatorPlanStep step : plan.steps()) {
            ILocatorStrategy strategy = registry.strategy(step.query().strategy());
            (strategy.phase() == LocatorStrategyPhase.DETERMINISTIC ? deterministic : fallback)
                    .add(new ExecutionUnit(strategy, step));
        }
        for (ILocatorStrategy strategy : registry.strategies()) {
            if (strategy.type() != LocatorStrategyType.CUSTOM || !strategy.supports(definition)) {
                continue;
            }
            LocatorBackendQuery query =
                    new LocatorBackendQuery(
                            LocatorStrategyType.CUSTOM,
                            definition.role(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty());
            ExecutionUnit unit =
                    new ExecutionUnit(strategy, new LocatorPlanStep(query, strategy.id()));
            (strategy.phase() == LocatorStrategyPhase.DETERMINISTIC ? deterministic : fallback)
                    .add(unit);
        }
        deterministic.addAll(fallback);
        return List.copyOf(deterministic);
    }

    private static int candidateLimit(LocatorConfig config, LocatorStrategyPhase phase) {
        if (phase == LocatorStrategyPhase.FALLBACK) {
            return Math.min(
                    config.resolutionBudget().maxCandidates(),
                    config.resolutionBudget().maxFuzzyCandidates());
        }
        return config.resolutionBudget().maxCandidates();
    }

    private static boolean earlyStop(
            List<LocatorCandidate> candidates, LocatorStrategyPhase phase, LocatorConfig config) {
        return phase == LocatorStrategyPhase.DETERMINISTIC
                && candidates.size() == 1
                && candidates.get(0).exactMatch()
                && candidates.get(0).hardConstraintsSatisfied()
                && candidates.get(0).confidence() >= config.earlyStopConfidence();
    }

    private static boolean awaitExactCandidate(
            LocatorDefinition definition,
            boolean waitForCandidates,
            List<LocatorCandidate> candidates) {
        return waitForCandidates
                && definition.accessibleName().isPresent()
                && definition.accessibleName().orElseThrow().type()
                        != io.webagent4j.locator.api.TextMatchType.FUZZY
                && candidates.stream().noneMatch(LocatorCandidate::exactMatch);
    }

    private static boolean ambiguous(List<LocatorCandidate> candidates, double margin) {
        if (candidates.size() < 2) {
            return false;
        }
        LocatorCandidate first = candidates.get(0);
        LocatorCandidate second = candidates.get(1);
        return LocatorCandidateOrder.sameSemanticTier(first, second)
                && Math.abs(first.score() - second.score()) <= margin;
    }

    private static List<LocatorCandidate> sorted(Map<String, LocatorCandidate> candidates) {
        return candidates.values().stream().sorted(CANDIDATE_ORDER).toList();
    }

    private static LocatorCandidate merge(LocatorCandidate left, LocatorCandidate right) {
        Map<String, LocatorEvidence> evidence = new LinkedHashMap<>();
        left.evidence().forEach(item -> evidence.put(evidenceKey(item), item));
        right.evidence()
                .forEach(
                        item ->
                                evidence.merge(
                                        evidenceKey(item),
                                        item,
                                        (first, second) ->
                                                first.contribution() >= second.contribution()
                                                        ? first
                                                        : second));
        List<LocatorEvidence> combined = List.copyOf(evidence.values());
        double score =
                Math.min(1.0, combined.stream().mapToDouble(LocatorEvidence::contribution).sum());
        LocatorCandidate primary = preferred(left, right);
        boolean exact = left.exactMatch() || right.exactMatch();
        double confidence =
                exact ? score : Math.min(score, Math.max(left.confidence(), right.confidence()));
        return new LocatorCandidate(
                left.identity(),
                primary.element(),
                primary.strategy(),
                score,
                confidence,
                Math.min(left.domOrder(), right.domOrder()),
                combined,
                exact,
                left.hardConstraintsSatisfied() && right.hardConstraintsSatisfied(),
                left.interactable() || right.interactable());
    }

    private static LocatorCandidate preferred(LocatorCandidate left, LocatorCandidate right) {
        return strategyRank(left.strategy()) <= strategyRank(right.strategy()) ? left : right;
    }

    private static int strategyRank(LocatorStrategyType strategy) {
        return switch (strategy) {
            case ACCESSIBLE_NAME -> 0;
            case LABEL -> 1;
            case ROLE -> 2;
            case TEST_ID, ID -> 3;
            case PLACEHOLDER, TITLE, ALT_TEXT, NAME_ATTRIBUTE, ATTRIBUTE -> 4;
            case VISIBLE_TEXT -> 5;
            case CSS, XPATH, DOM_RELATION -> 6;
            case FUZZY_TEXT -> 7;
            case CUSTOM -> 8;
        };
    }

    private static String evidenceKey(LocatorEvidence evidence) {
        return evidence.strategy()
                + "|"
                + evidence.matchType()
                + "|"
                + evidence.expected()
                + "|"
                + evidence.actual();
    }

    private LocatorNotFoundException notFound(LocatorContext context, Resolution resolution) {
        LocatorDiagnostics diagnostics =
                resolution
                        .diagnostics()
                        .snapshot(resolution.candidates(), Optional.empty(), Optional.empty());
        failed("not-found", diagnostics);
        return new LocatorNotFoundException(
                "No element matched within "
                        + context.timeoutFor(diagnostics.requestedLocator()).toMillis()
                        + " ms"
                        + System.lineSeparator()
                        + renderer.render(diagnostics, resolution.candidates()),
                diagnostics,
                failureStatus(diagnostics));
    }

    private static LocatorResolutionStatus failureStatus(LocatorDiagnostics diagnostics) {
        boolean interactionConstraint =
                diagnostics.requestedLocator().enabled().isPresent()
                        || diagnostics.requestedLocator().editable().isPresent()
                        || diagnostics.requestedLocator().inViewport().isPresent()
                        || diagnostics.requestedLocator().clickable().isPresent()
                        || diagnostics.requestedLocator().covered().isPresent();
        if (interactionConstraint && diagnostics.candidatesDiscovered() > 0) {
            return LocatorResolutionStatus.NOT_INTERACTABLE;
        }
        boolean explicitWait =
                diagnostics.requestedLocator().waitUntilVisible()
                        || diagnostics.requestedLocator().stability().isPresent();
        if (explicitWait && diagnostics.reachedLimits().contains(BudgetLimit.TIMEOUT)) {
            return LocatorResolutionStatus.TIMEOUT;
        }
        return LocatorResolutionStatus.UNRESOLVABLE;
    }

    private void completed(LocatorCandidate selected, LocatorDiagnostics diagnostics) {
        eventListener.onEvent(
                new ILocatorEvent.ResolutionCompleted(
                        Instant.now(),
                        selected.identity(),
                        selected.confidence(),
                        diagnostics.duration()));
        LOGGER.debug(
                "Locator completed policy={} strategy={} candidates={} score={} durationMs={}",
                diagnostics.resolutionPolicy(),
                selected.strategy(),
                diagnostics.candidatesDiscovered(),
                selected.score(),
                diagnostics.duration().toMillis());
    }

    private void failed(String reason, LocatorDiagnostics diagnostics) {
        eventListener.onEvent(
                new ILocatorEvent.ResolutionFailed(
                        Instant.now(), reason, Optional.of(diagnostics)));
        LOGGER.debug(
                "Locator failed policy={} reason={} candidates={} durationMs={}",
                diagnostics.resolutionPolicy(),
                reason,
                diagnostics.candidatesDiscovered(),
                diagnostics.duration().toMillis());
    }

    private static LocatorResult result(
            LocatorDefinition definition,
            LocatorCandidate selected,
            List<LocatorCandidate> candidates,
            LocatorDiagnostics diagnostics) {
        return new LocatorResult(
                definition,
                selected.element(),
                selected.strategy(),
                selected.score(),
                selected.confidence(),
                selected.exactMatch(),
                candidates,
                diagnostics);
    }

    private static void skipRemaining(
            List<ExecutionUnit> units,
            int start,
            LocatorDiagnosticsAccumulator diagnostics,
            String reason) {
        for (int index = start; index < units.size(); index++) {
            diagnostics.skipped(units.get(index).strategy().type(), reason);
        }
    }

    private static Duration atLeastOneNano(Duration duration) {
        return duration.compareTo(Duration.ofNanos(1)) < 0 ? Duration.ofNanos(1) : duration;
    }

    private record Resolution(
            List<LocatorCandidate> candidates, LocatorDiagnosticsAccumulator diagnostics) {

        private Resolution {
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
            Objects.requireNonNull(diagnostics, "diagnostics");
        }
    }

    private record Acceptance(int accepted, boolean hardConstraintRejected) {}

    private record ExecutionUnit(ILocatorStrategy strategy, LocatorPlanStep step) {

        private ExecutionUnit {
            Objects.requireNonNull(strategy, "strategy");
            Objects.requireNonNull(step, "step");
        }
    }
}
