package io.webagent4j.workflow;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable set of variables produced by a workflow execution's steps, exposed through {@link
 * WorkflowResult#output(WorkflowVariable)}. Rendering preserves the order values were published in.
 *
 * <p><b>Secret safety:</b> {@link #toString()} always masks every output declared {@link
 * WorkflowVariable#secret()} as {@code ***}, and also redacts every secret value known to the
 * execution that produced this result - including secret <em>inputs</em>, not only secret outputs -
 * out of every <em>public</em> output's rendering, so a secret cannot leak simply because its raw
 * text happens to also appear inside an unrelated public output. This applies equally to a {@link
 * WorkflowStatus#FAILED} result's already-produced outputs, and to a public output that was
 * published before a later step revealed that same text as a secret: the safe rendering below is
 * computed once, at final result construction, from every secret known to the execution up to that
 * point - not permanently at the moment each output was published. See {@code
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
        return Optional.of(variable.type().cast(entry.rawValue));
    }

    /**
     * Renders every output's name and its precomputed safe preview - see the class-level secret
     * safety note. Rendering is deterministic and does not re-invoke any output value's own {@code
     * toString()}.
     */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("WorkflowOutputs[");
        boolean first = true;
        for (Entry entry : values.values()) {
            if (!first) {
                text.append(", ");
            }
            first = false;
            text.append(entry.variable.name()).append('=').append(entry.safePreview);
        }
        return text.append(']').toString();
    }

    private record Entry(WorkflowVariable<?> variable, Object rawValue, String safePreview) {}

    /** Package-private mutable accumulator used only by {@link WorkflowEngine}. */
    static final class Builder {

        private record RawEntry(WorkflowVariable<?> variable, Object value) {}

        private final Map<String, RawEntry> values = new LinkedHashMap<>();

        <T> void put(WorkflowVariable<T> variable, T value) {
            values.put(variable.name(), new RawEntry(variable, value));
        }

        /**
         * Builds an immutable, insertion-order-preserving {@link WorkflowOutputs}, computing each
         * public output's safe preview by redacting every value in {@code activeSecrets} - every
         * secret known to the execution up to this point, not only this container's own secret
         * outputs - before bounding it, never the other order. {@code activeSecrets} is used only
         * transiently to compute previews here; no reference to it, or to any value it contains, is
         * retained by the returned {@link WorkflowOutputs}.
         */
        WorkflowOutputs build(Collection<String> activeSecrets) {
            SecretRedactor redactor = SecretRedactor.of(activeSecrets);
            Map<String, Entry> built = new LinkedHashMap<>();
            for (RawEntry raw : values.values()) {
                String preview =
                        raw.variable.secret()
                                ? "***"
                                : SafeRendering.bounded(
                                        redactor.redact(
                                                SafeRendering.renderPublicValueUnbounded(
                                                        raw.value)));
                built.put(raw.variable.name(), new Entry(raw.variable, raw.value, preview));
            }
            return new WorkflowOutputs(Collections.unmodifiableMap(built));
        }
    }
}
