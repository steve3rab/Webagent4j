package io.webagent4j.workflow;

import java.util.LinkedHashMap;
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
 * fallback, and no implicit default value.
 *
 * <p><b>Secret safety:</b> {@link #toString()} always masks every variable declared {@link
 * WorkflowVariable#secret()} as {@code ***}, regardless of its actual value. This is a masking
 * contract for framework-owned rendering, not encryption - see {@code
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

    /** Renders every input's name and, for non-secret values, a bounded value preview. */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("WorkflowInputs[");
        boolean first = true;
        for (Entry entry : values.values()) {
            if (!first) {
                text.append(", ");
            }
            first = false;
            text.append(entry.variable.name())
                    .append('=')
                    .append(SafeRendering.render(entry.variable, entry.value));
        }
        return text.append(']').toString();
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
         *     variable}'s declared type, or {@code variable}'s name was already supplied with a
         *     conflicting {@link WorkflowVariable} (different type or sensitivity)
         */
        public <T> Builder put(WorkflowVariable<T> variable, T value) {
            Objects.requireNonNull(variable, "variable");
            variable.requireValid(value);
            Entry existing = values.get(variable.name());
            if (existing != null && !existing.variable.equals(variable)) {
                throw new IllegalArgumentException(
                        "input '"
                                + variable.name()
                                + "' was already supplied with a conflicting variable"
                                + " declaration (different type or secret status)");
            }
            values.put(variable.name(), new Entry(variable, value));
            return this;
        }

        /** Builds an immutable, defensively copied {@link WorkflowInputs}. */
        public WorkflowInputs build() {
            return new WorkflowInputs(Map.copyOf(values));
        }
    }
}
