package io.webagent4j.locator.internal;

import io.webagent4j.locator.LocatorCandidate;
import io.webagent4j.locator.LocatorMatchType;
import io.webagent4j.locator.LocatorStrategyType;
import java.util.Comparator;

/** Deterministic candidate ordering and ambiguity-tier comparison. */
public final class LocatorCandidateOrder {

    private LocatorCandidateOrder() {}

    /** Returns the documented deterministic ranking comparator. */
    public static Comparator<LocatorCandidate> comparator() {
        return Comparator.comparing(LocatorCandidateOrder::roleExact)
                .reversed()
                .thenComparing(Comparator.comparing(LocatorCandidateOrder::nameExact).reversed())
                .thenComparing(Comparator.comparing(LocatorCandidateOrder::labelExact).reversed())
                .thenComparing(Comparator.comparing(LocatorCandidateOrder::otherExact).reversed())
                .thenComparing(Comparator.comparing(LocatorCandidateOrder::visible).reversed())
                .thenComparing(Comparator.comparing(LocatorCandidate::interactable).reversed())
                .thenComparing(Comparator.comparingDouble(LocatorCandidate::score).reversed())
                .thenComparing(Comparator.comparingDouble(LocatorCandidate::confidence).reversed())
                .thenComparingInt(LocatorCandidate::domOrder)
                .thenComparing(LocatorCandidate::identity);
    }

    /** Returns whether candidates occupy the same semantic ambiguity tier. */
    public static boolean sameSemanticTier(LocatorCandidate left, LocatorCandidate right) {
        return roleExact(left) == roleExact(right)
                && nameExact(left) == nameExact(right)
                && labelExact(left) == labelExact(right)
                && otherExact(left) == otherExact(right)
                && visible(left) == visible(right)
                && left.interactable() == right.interactable()
                && left.exactMatch() == right.exactMatch();
    }

    private static boolean roleExact(LocatorCandidate candidate) {
        return exact(candidate, LocatorStrategyType.ROLE);
    }

    private static boolean nameExact(LocatorCandidate candidate) {
        return exact(candidate, LocatorStrategyType.ACCESSIBLE_NAME);
    }

    private static boolean labelExact(LocatorCandidate candidate) {
        return exact(candidate, LocatorStrategyType.LABEL);
    }

    private static boolean otherExact(LocatorCandidate candidate) {
        return candidate.evidence().stream()
                .anyMatch(
                        evidence ->
                                evidence.matchType() != LocatorMatchType.STATE
                                        && evidence.matchType() != LocatorMatchType.FUZZY
                                        && evidence.strategy() != LocatorStrategyType.ROLE
                                        && evidence.strategy()
                                                != LocatorStrategyType.ACCESSIBLE_NAME
                                        && evidence.strategy() != LocatorStrategyType.LABEL);
    }

    private static boolean visible(LocatorCandidate candidate) {
        return candidate.evidence().stream()
                .anyMatch(
                        evidence ->
                                evidence.matchType() == LocatorMatchType.STATE
                                        && evidence.expected().equals("visible"));
    }

    private static boolean exact(LocatorCandidate candidate, LocatorStrategyType strategy) {
        return candidate.evidence().stream()
                .anyMatch(
                        evidence ->
                                evidence.strategy() == strategy
                                        && evidence.matchType() == LocatorMatchType.EXACT);
    }
}
