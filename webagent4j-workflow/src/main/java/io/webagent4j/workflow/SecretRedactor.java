package io.webagent4j.workflow;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic, exact-value secret redaction over a fixed set of known secret strings - not public
 * API.
 *
 * <p>This is masking, not encryption: it replaces every exact occurrence of a known secret value in
 * a text with {@code ***}. Values are matched longest-first, so one secret that is a substring of
 * another can never leave a partial, still-identifying fragment behind (see {@code
 * docs/workflow.md#secret-masking}). An instance is built fresh from whatever secret values are
 * currently known - by {@code WorkflowEngine}'s session for failure/condition diagnostics, or by
 * {@link WorkflowInputs}/{@link WorkflowOutputs} themselves for cross-field rendering - so this
 * class holds no static or otherwise shared state between calls.
 */
final class SecretRedactor {

    private static final String MASK = "***";

    private final List<String> secretsLongestFirst;

    private SecretRedactor(List<String> secretsLongestFirst) {
        this.secretsLongestFirst = secretsLongestFirst;
    }

    /** Builds a redactor over the given known secret values (blank/empty values are ignored). */
    static SecretRedactor of(Collection<String> secretValues) {
        List<String> distinct =
                secretValues.stream()
                        .filter(value -> value != null && !value.isEmpty())
                        .distinct()
                        .sorted(Comparator.comparingInt(String::length).reversed())
                        .toList();
        return new SecretRedactor(distinct);
    }

    /** Returns {@code text} with every known secret value replaced by {@code ***}. */
    String redact(String text) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (String secret : secretsLongestFirst) {
            result = result.replace(secret, MASK);
        }
        return result;
    }
}
