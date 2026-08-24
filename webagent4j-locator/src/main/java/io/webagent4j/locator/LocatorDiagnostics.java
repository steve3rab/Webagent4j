package io.webagent4j.locator;

import io.webagent4j.locator.api.LocatorDefinition;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable machine-readable resolution diagnostics. Detailed rejected-candidate data is bounded by
 * the configured candidate budget and retained only in detailed mode.
 *
 * @param requestedLocator immutable requested locator
 * @param resolutionPolicy applied policy
 * @param diagnosticsLevel retained detail level
 * @param scopePath hierarchical scope path
 * @param strategiesExecuted ordered strategy executions
 * @param strategiesSkipped ordered skipped strategies
 * @param candidatesDiscovered raw candidates reported by backends
 * @param candidatesDeduplicated duplicate discoveries merged into existing candidates
 * @param candidatesRejected candidates rejected by constraints or thresholds
 * @param filtersApplied ordered hard constraints
 * @param exactMatches accepted candidates with decisive exact evidence
 * @param fuzzyMatches accepted candidates with fuzzy evidence
 * @param selectedCandidate selected candidate when resolution succeeded
 * @param duration total elapsed resolution time
 * @param budgetReached whether one or more work limits were reached
 * @param reachedLimits exact reached limits
 * @param ambiguity ambiguity details when {@code single()} could not select safely
 * @param rejectedCandidates bounded detailed rejection records
 */
public record LocatorDiagnostics(
        LocatorDefinition requestedLocator,
        LocatorResolutionPolicy resolutionPolicy,
        LocatorDiagnosticsLevel diagnosticsLevel,
        List<String> scopePath,
        List<StrategyExecution> strategiesExecuted,
        List<SkippedStrategy> strategiesSkipped,
        int candidatesDiscovered,
        int candidatesDeduplicated,
        int candidatesRejected,
        List<String> filtersApplied,
        int exactMatches,
        int fuzzyMatches,
        Optional<LocatorCandidate> selectedCandidate,
        Duration duration,
        boolean budgetReached,
        Set<BudgetLimit> reachedLimits,
        Optional<Ambiguity> ambiguity,
        List<RejectedCandidate> rejectedCandidates) {

    /** Validates counts and defensively stores every collection. */
    public LocatorDiagnostics {
        Objects.requireNonNull(requestedLocator, "requestedLocator");
        Objects.requireNonNull(resolutionPolicy, "resolutionPolicy");
        Objects.requireNonNull(diagnosticsLevel, "diagnosticsLevel");
        scopePath = List.copyOf(Objects.requireNonNull(scopePath, "scopePath"));
        strategiesExecuted =
                List.copyOf(Objects.requireNonNull(strategiesExecuted, "strategiesExecuted"));
        strategiesSkipped =
                List.copyOf(Objects.requireNonNull(strategiesSkipped, "strategiesSkipped"));
        filtersApplied = List.copyOf(Objects.requireNonNull(filtersApplied, "filtersApplied"));
        selectedCandidate = Objects.requireNonNull(selectedCandidate, "selectedCandidate");
        Objects.requireNonNull(duration, "duration");
        reachedLimits = Set.copyOf(Objects.requireNonNull(reachedLimits, "reachedLimits"));
        ambiguity = Objects.requireNonNull(ambiguity, "ambiguity");
        rejectedCandidates =
                List.copyOf(Objects.requireNonNull(rejectedCandidates, "rejectedCandidates"));
        if (candidatesDiscovered < 0
                || candidatesDeduplicated < 0
                || candidatesRejected < 0
                || exactMatches < 0
                || fuzzyMatches < 0) {
            throw new IllegalArgumentException("diagnostic counts cannot be negative");
        }
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration cannot be negative");
        }
        if (budgetReached != !reachedLimits.isEmpty()) {
            throw new IllegalArgumentException("budgetReached must agree with reachedLimits");
        }
    }

    /** One ordered strategy execution summary. */
    public record StrategyExecution(
            LocatorStrategyType strategy,
            Duration duration,
            int candidatesDiscovered,
            int candidatesAccepted) {

        /** Validates execution data. */
        public StrategyExecution {
            Objects.requireNonNull(strategy, "strategy");
            Objects.requireNonNull(duration, "duration");
            if (duration.isNegative()) {
                throw new IllegalArgumentException("duration cannot be negative");
            }
            if (candidatesDiscovered < 0 || candidatesAccepted < 0) {
                throw new IllegalArgumentException("strategy counts cannot be negative");
            }
        }
    }

    /** One strategy omitted by policy, capability or budget. */
    public record SkippedStrategy(LocatorStrategyType strategy, String reason) {

        /** Validates skipped-strategy data. */
        public SkippedStrategy {
            Objects.requireNonNull(strategy, "strategy");
            reason = Objects.requireNonNull(reason, "reason");
        }
    }

    /** One bounded detailed rejection. */
    public record RejectedCandidate(
            String identity, LocatorStrategyType strategy, RejectionReason reason) {

        /** Validates rejection data. */
        public RejectedCandidate {
            identity = Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(strategy, "strategy");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Best and second-best candidates considered ambiguous. */
    public record Ambiguity(
            String firstIdentity,
            double firstScore,
            String secondIdentity,
            double secondScore,
            double margin) {

        /** Validates ambiguity scores. */
        public Ambiguity {
            firstIdentity = Objects.requireNonNull(firstIdentity, "firstIdentity");
            secondIdentity = Objects.requireNonNull(secondIdentity, "secondIdentity");
            validateUnit(firstScore, "firstScore");
            validateUnit(secondScore, "secondScore");
            validateUnit(margin, "margin");
        }
    }

    /** Candidate rejection categories exposed in detailed diagnostics. */
    public enum RejectionReason {
        ROLE_MISMATCH,
        NAME_MISMATCH,
        NOT_VISIBLE,
        DISABLED,
        NOT_EDITABLE,
        NOT_READ_ONLY,
        NOT_CHECKED,
        NOT_SELECTED,
        NOT_FOCUSED,
        OUTSIDE_VIEWPORT,
        NOT_CLICKABLE,
        NOT_COVERED,
        ATTRIBUTE_MISMATCH,
        BELOW_THRESHOLD,
        DUPLICATE,
        OUTSIDE_SCOPE
    }

    /** Resolution budget limits represented in diagnostics. */
    public enum BudgetLimit {
        TIMEOUT,
        CANDIDATES,
        STRATEGIES,
        FUZZY_CANDIDATES
    }

    private static void validateUnit(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be between zero and one");
        }
    }
}
