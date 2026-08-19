package io.webagent4j.locator.internal;

import io.webagent4j.locator.LocatorBackendSearchResult;
import io.webagent4j.locator.LocatorCandidate;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorDiagnostics;
import io.webagent4j.locator.LocatorDiagnostics.Ambiguity;
import io.webagent4j.locator.LocatorDiagnostics.BudgetLimit;
import io.webagent4j.locator.LocatorDiagnostics.RejectedCandidate;
import io.webagent4j.locator.LocatorDiagnostics.RejectionReason;
import io.webagent4j.locator.LocatorDiagnostics.SkippedStrategy;
import io.webagent4j.locator.LocatorDiagnostics.StrategyExecution;
import io.webagent4j.locator.LocatorDiagnosticsLevel;
import io.webagent4j.locator.LocatorScope;
import io.webagent4j.locator.LocatorStrategyType;
import io.webagent4j.locator.api.LocatorDefinition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Bounded mutable accumulator owned by exactly one resolution attempt. */
public final class LocatorDiagnosticsAccumulator {

    private final LocatorDefinition definition;
    private final LocatorConfig config;
    private LocatorScope scope;
    private final long startedNanos;
    private final List<StrategyExecution> executed = new ArrayList<>();
    private final List<SkippedStrategy> skipped = new ArrayList<>();
    private final List<RejectedCandidate> rejected = new ArrayList<>();
    private final Set<BudgetLimit> limits = EnumSet.noneOf(BudgetLimit.class);
    private int candidatesDiscovered;
    private int candidatesDeduplicated;
    private int candidatesRejected;

    /** Starts diagnostics for one definition and scope. */
    public LocatorDiagnosticsAccumulator(
            LocatorDefinition definition, LocatorConfig config, LocatorScope scope) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.config = Objects.requireNonNull(config, "config");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.startedNanos = System.nanoTime();
    }

    /**
     * Updates the scope this accumulator reports, reflecting the most recently, successfully
     * resolved live context - relevant when the resolved scope can change between polling attempts
     * (a structured semantic scope re-resolved fresh on every attempt).
     */
    public void scope(LocatorScope scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    /** Records one executed strategy. */
    public void executed(
            LocatorStrategyType strategy,
            Duration duration,
            LocatorBackendSearchResult result,
            int accepted) {
        candidatesDiscovered += result.discoveredCount();
        if (config.diagnosticsLevel() != LocatorDiagnosticsLevel.OFF) {
            executed.add(
                    new StrategyExecution(strategy, duration, result.discoveredCount(), accepted));
        }
        if (result.truncated()) {
            limit(BudgetLimit.CANDIDATES);
        }
    }

    /** Records one skipped strategy. */
    public void skipped(LocatorStrategyType strategy, String reason) {
        if (config.diagnosticsLevel() != LocatorDiagnosticsLevel.OFF) {
            skipped.add(new SkippedStrategy(strategy, reason));
        }
    }

    /** Records one duplicate discovery merged into an existing candidate. */
    public void deduplicated(String identity, LocatorStrategyType strategy) {
        candidatesDeduplicated++;
        if (config.diagnosticsLevel() == LocatorDiagnosticsLevel.DETAILED
                && rejected.size() < config.resolutionBudget().maxCandidates()) {
            rejected.add(new RejectedCandidate(identity, strategy, RejectionReason.DUPLICATE));
        }
    }

    /** Records one rejected candidate and its principal reason. */
    public void rejected(String identity, LocatorStrategyType strategy, RejectionReason reason) {
        candidatesRejected++;
        if (config.diagnosticsLevel() == LocatorDiagnosticsLevel.DETAILED
                && rejected.size() < config.resolutionBudget().maxCandidates()) {
            rejected.add(new RejectedCandidate(identity, strategy, reason));
        }
    }

    /** Marks one reached budget limit. */
    public void limit(BudgetLimit limit) {
        limits.add(Objects.requireNonNull(limit, "limit"));
    }

    /** Builds an immutable diagnostics snapshot. */
    public LocatorDiagnostics snapshot(
            List<LocatorCandidate> candidates,
            Optional<LocatorCandidate> selected,
            Optional<Ambiguity> ambiguity) {
        int exact = (int) candidates.stream().filter(LocatorCandidate::exactMatch).count();
        int fuzzy =
                (int)
                        candidates.stream()
                                .filter(
                                        candidate ->
                                                candidate.hasEvidence(
                                                        LocatorStrategyType.FUZZY_TEXT))
                                .count();
        return new LocatorDiagnostics(
                definition,
                config.resolutionPolicy(),
                config.diagnosticsLevel(),
                scope.path(),
                executed,
                skipped,
                candidatesDiscovered,
                candidatesDeduplicated,
                candidatesRejected,
                filters(definition),
                exact,
                fuzzy,
                selected,
                elapsed(),
                !limits.isEmpty(),
                limits,
                ambiguity,
                rejected);
    }

    /** Returns the elapsed monotonic duration. */
    public Duration elapsed() {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedNanos));
    }

    private static List<String> filters(LocatorDefinition definition) {
        List<String> filters = new ArrayList<>();
        definition.role().ifPresent(ignored -> filters.add("role"));
        definition.visible().ifPresent(ignored -> filters.add("visible"));
        definition.enabled().ifPresent(ignored -> filters.add("enabled"));
        definition.editable().ifPresent(ignored -> filters.add("editable"));
        definition.readOnly().ifPresent(ignored -> filters.add("readonly"));
        definition.checked().ifPresent(ignored -> filters.add("checked"));
        definition.selected().ifPresent(ignored -> filters.add("selected"));
        definition.focused().ifPresent(ignored -> filters.add("focused"));
        definition.inViewport().ifPresent(ignored -> filters.add("inViewport"));
        definition.clickable().ifPresent(ignored -> filters.add("clickable"));
        definition.covered().ifPresent(ignored -> filters.add("covered"));
        if (!definition.attributes().isEmpty()) {
            filters.add("attributes");
        }
        return List.copyOf(filters);
    }
}
