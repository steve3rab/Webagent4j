package io.webagent4j.locator;

import io.webagent4j.locator.api.LocatorDefinition;
import java.util.List;
import java.util.Locale;

/** Renders stable human-readable locator diagnostics without owning resolution behavior. */
public final class LocatorDiagnosticsRenderer {

    /** Renders a successful or failed diagnostics snapshot. */
    public String render(LocatorDiagnostics diagnostics, List<LocatorCandidate> candidates) {
        StringBuilder output = new StringBuilder();
        appendRequest(output, diagnostics.requestedLocator());
        output.append("Policy: ")
                .append(diagnostics.resolutionPolicy())
                .append(System.lineSeparator());
        output.append("Scope: ")
                .append(String.join(" -> ", diagnostics.scopePath()))
                .append(System.lineSeparator())
                .append("Candidates:")
                .append(System.lineSeparator());
        appendCandidates(output, candidates);
        diagnostics
                .ambiguity()
                .ifPresent(
                        value ->
                                output.append("Ambiguous within margin ")
                                        .append(format(value.margin()))
                                        .append(System.lineSeparator()));
        diagnostics
                .selectedCandidate()
                .ifPresent(
                        candidate ->
                                output.append("Selected candidate #1")
                                        .append(System.lineSeparator())
                                        .append("score=")
                                        .append(format(candidate.score()))
                                        .append(System.lineSeparator())
                                        .append("confidence=")
                                        .append(format(candidate.confidence())));
        return output.toString().stripTrailing();
    }

    private static void appendRequest(StringBuilder output, LocatorDefinition definition) {
        output.append("Requested:").append(System.lineSeparator());
        definition
                .role()
                .ifPresent(
                        role ->
                                output.append("  role = ")
                                        .append(role)
                                        .append(System.lineSeparator()));
        definition
                .accessibleName()
                .ifPresent(
                        name ->
                                output.append("  name = \"")
                                        .append(name.value())
                                        .append("\" (")
                                        .append(name.type())
                                        .append(')')
                                        .append(System.lineSeparator()));
        definition
                .label()
                .ifPresent(
                        label ->
                                output.append("  label = \"")
                                        .append(label.value())
                                        .append('\"')
                                        .append(System.lineSeparator()));
        definition
                .css()
                .ifPresent(
                        ignored ->
                                output.append("  css = <explicit>").append(System.lineSeparator()));
        definition
                .xpath()
                .ifPresent(
                        ignored ->
                                output.append("  xpath = <explicit>")
                                        .append(System.lineSeparator()));
    }

    private static void appendCandidates(StringBuilder output, List<LocatorCandidate> candidates) {
        if (candidates.isEmpty()) {
            output.append("  none").append(System.lineSeparator());
            return;
        }
        for (int index = 0; index < candidates.size(); index++) {
            LocatorCandidate candidate = candidates.get(index);
            output.append(index + 1)
                    .append(". ")
                    .append(candidate.element().role())
                    .append(" \"")
                    .append(candidate.element().accessibleName())
                    .append('\"')
                    .append(System.lineSeparator());
            for (LocatorEvidence evidence : candidate.evidence()) {
                output.append("   ")
                        .append(evidence.strategy())
                        .append(' ')
                        .append(evidence.matchType())
                        .append(" +")
                        .append(format(evidence.contribution()))
                        .append(System.lineSeparator());
            }
            output.append("   visible=")
                    .append(candidate.element().visible())
                    .append(System.lineSeparator())
                    .append("   enabled=")
                    .append(candidate.element().enabled())
                    .append(System.lineSeparator())
                    .append("   score=")
                    .append(format(candidate.score()))
                    .append(System.lineSeparator());
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
