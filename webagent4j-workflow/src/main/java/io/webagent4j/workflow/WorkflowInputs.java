package io.webagent4j.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, explicit set of input values supplied to one {@link WorkflowEngine#execute(Workflow,
 * WorkflowInputs)} call.
 *
 * <p>Values are never stored in a raw, publicly exposed {@code Map<String, Object>}: each is keyed
 * by its {@link WorkflowVariable}, validated against that variable's declared type at {@link
 * Builder#put}. Inputs are explicit only - there is no environment-variable or system-property
 * fallback, and no implicit default value. Rendering preserves the order values were supplied to
 * the builder in.
 *
 * <p><b>Write-once:</b> {@link Builder#put} rejects a second call for a name already supplied -
 * with the same value, an equal declaration, or a conflicting one - there is no last-write-wins.
 *
 * <p><b>Secret safety:</b> {@link #toString()} always masks every variable declared {@link
 * WorkflowVariable#secret()} as {@code ***}, regardless of its actual value. It also redacts every
 * currently-known secret value out of every <em>public</em> field's rendering, so a secret cannot
 * leak simply because its raw text happens to also appear inside an unrelated public value. This is
 * a masking contract for framework-owned rendering, not encryption - see {@code
 * docs/workflow.md#secret-masking}.
 */
public final class WorkflowInputs {

    private final Map<String, Entry> values;

    private WorkflowInputs(Map<String, Entry> values) {
        this.values = values;
    }

    /** Returns a new, empty builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns an empty set of inputs. */
    public static WorkflowInputs empty() {
        return new WorkflowInputs(Map.of());
    }

    /** Returns the current value of {@code variable}, if supplied. */
    public <T> Optional<T> find(WorkflowVariable<T> variable) {
        Objects.requireNonNull(variable, "variable");
        Entry entry = values.get(variable.name());
        if (entry == null || !entry.variable.equals(variable)) {
            return Optional.empty();
        }
        return Optional.of(variable.type().cast(entry.value));
    }

    /** Returns whether {@code variable} was supplied. */
    public boolean exists(WorkflowVariable<?> variable) {
        Objects.requireNonNull(variable, "variable");
        Entry entry = values.get(variable.name());
        return entry != null && entry.variable.equals(variable);
    }

    /** Returns every supplied entry, keyed by variable name - for {@link WorkflowEngine}'s use. */
    Map<String, Entry> entries() {
        return values;
    }

    /**
     * Renders every input's name and a bounded value preview, masking every secret value -
     * including a secret's raw text if it happens to appear inside an unrelated public value.
     */
    @Override
    public String toString() {
        SecretRedactor redactor = SecretRedactor.of(activeSecretValues());
        StringBuilder text = new StringBuilder("WorkflowInputs[");
        boolean first = true;
        for (Entry entry : values.values()) {
            if (!first) {
                text.append(", ");
            }
            first = false;
            String rendered =
                    entry.variable.secret()
                            ? "***"
                            : SafeRendering.bounded(
                                    redactor.redact(
                                            SafeRendering.renderPublicValueUnbounded(entry.value)));
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

    /** One validated (variable, value) pair. */
    record Entry(WorkflowVariable<?> variable, Object value) {}

    /** Mutable builder for {@link WorkflowInputs}. */
    public static final class Builder {

        private final Map<String, Entry> values = new LinkedHashMap<>();

        private Builder() {}

        /**
         * Supplies {@code value} for {@code variable}, validated against its declared type.
         *
         * @throws IllegalArgumentException if {@code value} is null, not assignable to {@code
         *     variable}'s declared type, or {@code variable}'s name was already supplied - even
         *     with an equal value or an equal declaration; write-once, never last-write-wins
         */
        public <T> Builder put(WorkflowVariable<T> variable, T value) {
            Objects.requireNonNull(variable, "variable");
            variable.requireValid(value);
            if (values.containsKey(variable.name())) {
                throw new IllegalArgumentException(
                        "input '" + variable.name() + "' was already supplied");
            }
            values.put(variable.name(), new Entry(variable, value));
            return this;
        }

        /**
         * Builds an immutable, insertion-order-preserving, defensively copied {@link
         * WorkflowInputs}.
         */
        public WorkflowInputs build() {
            return new WorkflowInputs(Collections.unmodifiableMap(new LinkedHashMap<>(values)));
        }
    }
}
