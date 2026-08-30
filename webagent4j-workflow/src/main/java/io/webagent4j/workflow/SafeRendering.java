package io.webagent4j.workflow;

/**
 * Package-private helper for bounded, secret-aware rendering shared by public workflow types.
 *
 * <p><b>{@code render → redact → bound}, never the reverse:</b> {@link #renderPublicValueUnbounded}
 * and {@link #render} are deliberately unbounded, since bounding a rendered value before it has
 * been redacted against every known secret could truncate a secret mid-value and leave a
 * still-identifying partial fragment in the final text. A caller must always redact the unbounded
 * result before calling {@link #bounded(String)}.
 *
 * <p><b>Resource bounding is about retention, not rendering:</b> this class cannot stop an
 * arbitrary caller-supplied {@code toString()} from allocating heavily, or from being slow, while
 * it runs - that cost is inherent to the value the caller chose to render and is not something a
 * framework can eliminate. What this module bounds is how long <em>it</em> retains the result:
 * {@link WorkflowConditions}' built-in conditions defer calling {@link #render} at all until the
 * workflow's complete secret set is known (see {@link IDeferredConditionDescription}), so the
 * unbounded rendered text is created, redacted, and immediately bounded by {@link WorkflowEngine} -
 * never held onto for the rest of an execution. {@link WorkflowInputs}/{@link WorkflowOutputs}
 * already compute their previews the same way - transiently, within one method call. A
 * caller-supplied {@link IWorkflowCondition}'s own {@code describe()} text is a different case: the
 * engine cannot defer calling code it does not own, so it retains whatever text that implementation
 * returns until finalization, exactly as it always has - see {@code
 * docs/workflow.md#resource-bounded-diagnostics}.
 */
final class SafeRendering {

    private static final int MAX_RENDERED_VALUE_LENGTH = 200;

    private SafeRendering() {}

    /**
     * Renders {@code value} for {@code variable}: masked as {@code ***} if the variable is secret,
     * otherwise a crash-safe rendering of the value itself. Deliberately <b>not bounded</b> - see
     * the class Javadoc. Called from exactly two places: {@link WorkflowConditions}' built-in
     * conditions' {@code describeFinal} ({@link IDeferredConditionDescription}), at workflow
     * finalization, when the complete secret set is already known, and their {@code describe()}
     * (called directly, outside {@code WorkflowEngine}, per {@link IWorkflowCondition#describe}'s
     * own contract - this caller intentionally accepts an unbounded, unretained result). In both
     * cases the caller must redact the result before calling {@link #bounded(String)}; the class
     * Javadoc explains why bounding first is unsafe, not merely undesirable.
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
     * none of that may crash framework-owned rendering. Called from {@link #render}, and directly
     * by {@link WorkflowInputs#toString()} and {@link WorkflowOutputs.Builder#build}, both of which
     * redact and call {@link #bounded(String)} on the result within the same method call - the
     * unbounded text this method returns is never stored in a field or otherwise retained past that
     * one call.
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
