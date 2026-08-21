package io.webagent4j.workflow;

/** Package-private helper for bounded, secret-aware rendering shared by public workflow types. */
final class SafeRendering {

    private static final int MAX_RENDERED_VALUE_LENGTH = 200;

    private SafeRendering() {}

    /**
     * Renders {@code value} for {@code variable}: masked as {@code ***} if the variable is secret,
     * otherwise a crash-safe rendering of the value itself. Deliberately <b>not bounded</b>: a
     * built-in condition (see {@code WorkflowConditions}) uses this for its literal comparison
     * value at definition time, before any execution-time secret is known. A caller that redacts
     * against known secrets - {@code WorkflowEngine} for a stored condition description, {@link
     * WorkflowInputs}, {@link WorkflowOutputs} - must redact this unbounded text first and only
     * then call {@link #bounded(String)}; bounding before redaction can truncate a secret mid-value
     * and leak a partial, still-identifying fragment.
     */
    static String render(WorkflowVariable<?> variable, Object value) {
        if (variable.secret()) {
            return "***";
        }
        return renderPublicValueUnbounded(value);
    }

    /**
     * Renders an arbitrary public value defensively and without bounding: a caller-supplied
     * object's {@code toString()} may throw, return an enormous string, or be nondeterministic, and
     * none of that may crash framework-owned rendering. Callers must redact the result against
     * known secrets before calling {@link #bounded(String)} - never the other order.
     */
    static String renderPublicValueUnbounded(Object value) {
        try {
            String rendered = String.valueOf(value);
            return rendered == null ? "null" : rendered;
        } catch (RuntimeException e) {
            return "<rendering-failed:" + e.getClass().getSimpleName() + ">";
        }
    }

    /** Bounds an already-redacted, safe rendered value to a deterministic maximum length. */
    static String bounded(String rendered) {
        String safe = rendered == null ? "null" : rendered;
        if (safe.length() > MAX_RENDERED_VALUE_LENGTH) {
            return safe.substring(0, MAX_RENDERED_VALUE_LENGTH) + "...(truncated)";
        }
        return safe;
    }
}
