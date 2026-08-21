package io.webagent4j.workflow;

/** Package-private helper for bounded, secret-aware rendering shared by public workflow types. */
final class SafeRendering {

    private static final int MAX_RENDERED_VALUE_LENGTH = 200;

    private SafeRendering() {}

    /**
     * Renders {@code value} for {@code variable}: masked as {@code ***} if the variable is secret,
     * otherwise a bounded, crash-safe rendering of the value itself. Suitable for a single value
     * where no other field's secret could appear inside it (a condition's literal comparison value,
     * fixed at definition time). A container holding multiple fields must instead redact every
     * rendered value against every other field's secret; see {@code WorkflowInputs}/{@code
     * WorkflowOutputs}, which use {@link #renderPublicValue(Object)} directly for that.
     */
    static String render(WorkflowVariable<?> variable, Object value) {
        if (variable.secret()) {
            return "***";
        }
        return renderPublicValue(value);
    }

    /**
     * Renders an arbitrary public value defensively: a caller-supplied object's {@code toString()}
     * may throw, return an enormous string, or be nondeterministic, and none of that may crash or
     * inflate framework-owned rendering.
     */
    static String renderPublicValue(Object value) {
        String rendered;
        try {
            rendered = String.valueOf(value);
        } catch (RuntimeException e) {
            return "<rendering-failed:" + e.getClass().getSimpleName() + ">";
        }
        return bounded(rendered);
    }

    /** Bounds an already-safe rendered value to a deterministic maximum length. */
    static String bounded(String rendered) {
        String safe = rendered == null ? "null" : rendered;
        if (safe.length() > MAX_RENDERED_VALUE_LENGTH) {
            return safe.substring(0, MAX_RENDERED_VALUE_LENGTH) + "...(truncated)";
        }
        return safe;
    }
}
