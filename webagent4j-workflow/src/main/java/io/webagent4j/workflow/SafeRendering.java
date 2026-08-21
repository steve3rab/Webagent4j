package io.webagent4j.workflow;

/** Package-private helper for bounded, secret-aware rendering shared by public workflow types. */
final class SafeRendering {

    private static final int MAX_RENDERED_VALUE_LENGTH = 200;

    private SafeRendering() {}

    /**
     * Renders {@code value} for {@code variable}, masking it as {@code ***} if the variable is
     * secret and otherwise bounding the length of {@link String#valueOf(Object)}.
     */
    static String render(WorkflowVariable<?> variable, Object value) {
        if (variable.secret()) {
            return "***";
        }
        return bounded(String.valueOf(value));
    }

    /** Bounds an already-safe (non-secret) rendered value to a deterministic maximum length. */
    static String bounded(String rendered) {
        if (rendered.length() > MAX_RENDERED_VALUE_LENGTH) {
            return rendered.substring(0, MAX_RENDERED_VALUE_LENGTH) + "...(truncated)";
        }
        return rendered;
    }
}
