package io.webagent4j.action;

import java.util.List;
import java.util.Objects;

/**
 * The ordered sequence of governed-execution decisions made for one action, in the exact order they
 * were evaluated (for example: action-policy pre-execution, network-policy pre-execution,
 * network-policy post-execution) - never reordered by kind or outcome.
 *
 * <p>Obtained from {@link ActionResult#decisionTrace()}, derived from that result's own {@link
 * ActionResult#events()} stream. Empty whenever no governed-execution policy was configured for the
 * action, including for every value produced by one of {@code ActionResult}'s compatibility
 * constructors.
 */
public record ActionDecisionTrace(List<ActionDecisionEntry> entries) {

    private static final ActionDecisionTrace EMPTY = new ActionDecisionTrace(List.of());

    /** Validates and defensively copies {@code entries}. */
    public ActionDecisionTrace {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    /** Returns the shared empty trace. */
    public static ActionDecisionTrace empty() {
        return EMPTY;
    }

    /** Returns whether any governed-execution decision was recorded. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
