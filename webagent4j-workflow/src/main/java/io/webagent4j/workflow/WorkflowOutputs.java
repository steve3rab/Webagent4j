package io.webagent4j.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable set of variables produced by a workflow execution's steps, exposed through {@link
 * WorkflowResult#output(WorkflowVariable)}. Rendering preserves the order values were published in.
 *
 * <p><b>Secret safety:</b> {@link #toString()} always masks every output declared {@link
 * WorkflowVariable#secret()} as {@code ***}, and also redacts every currently-known secret value
 * out of every <em>public</em> output's rendering, so a secret cannot leak simply because its raw
 * text happens to also appear inside an unrelated public output - see {@code
 * docs/workflow.md#secret-masking}.
 */
public final class WorkflowOutputs {

    private static final WorkflowOutputs EMPTY = new WorkflowOutputs(Map.of());

    private final Map<String, Entry> values;

    private WorkflowOutputs(Map<String, Entry> values) {
        this.values = values;
    }

    /** Returns an empty set of outputs. */
    static WorkflowOutputs empty() {
        return EMPTY;
    }

    /** Returns the value published for {@code variable}, if any step produced it. */
    public <T> Optional<T> find(WorkflowVariable<T> variable) {
        Objects.requireNonNull(variable, "variable");
        Entry entry = values.get(variable.name());
        if (entry == null || !entry.variable.equals(variable)) {
            return Optional.empty();
        }
        return Optional.of(variable.type().cast(entry.value));
    }

    /**
     * Renders every output's name and a bounded value preview, masking every secret value -
     * including a secret's raw text if it happens to appear inside an unrelated public value.
     */
    @Override
    public String toString() {
        SecretRedactor redactor = SecretRedactor.of(activeSecretValues());
        StringBuilder text = new StringBuilder("WorkflowOutputs[");
        boolean first = true;
        for (Entry entry : values.values()) {
            if (!first) {
                text.append(", ");
            }
            first = false;
            String rendered =
                    entry.variable.secret()
                            ? "***"
                            : redactor.redact(SafeRendering.renderPublicValue(entry.value));
            text.append(entry.variable.name()).append('=').append(rendered);
        }
        return text.append(']').toString();
    }

    private List<String> activeSecretValues() {
        List<String> secretValues = new ArrayList<>();
        for (Entry entry : values.values()) {
            if (entry.variable.secret() && entry.value instanceof String secretValue) {
                secretValues.add(secretValue);
            }
        }
        return secretValues;
    }

    private record Entry(WorkflowVariable<?> variable, Object value) {}

    /** Package-private mutable accumulator used only by {@link WorkflowEngine}. */
    static final class Builder {

        private final Map<String, Entry> values = new LinkedHashMap<>();

        <T> void put(WorkflowVariable<T> variable, T value) {
            values.put(variable.name(), new Entry(variable, value));
        }

        WorkflowOutputs build() {
            return new WorkflowOutputs(Collections.unmodifiableMap(new LinkedHashMap<>(values)));
        }
    }
}
