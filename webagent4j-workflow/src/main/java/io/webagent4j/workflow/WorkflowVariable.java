package io.webagent4j.workflow;

import java.util.Objects;

/**
 * Typed key identifying one workflow variable by name, runtime type, and sensitivity.
 *
 * <p>Workflows never use {@code Map<String, Object>} as their primary variable API: every value is
 * read and written through a {@code WorkflowVariable<T>}, whose declared {@link #type()} is used to
 * validate a runtime value at the point it enters {@link WorkflowInputs} or is published as a step
 * output. This is deliberately a check against the reifiable {@link Class}, not a full generic
 * {@code TypeToken} framework: for a generic container type such as {@code List<String>}, only the
 * raw {@code List} shape is verified, never its element type.
 *
 * <p>Two variables are the same logical variable only when their {@link #name()}, {@link #type()},
 * and {@link #secret()} all agree; {@link Workflow.Builder#build()} rejects a workflow that reuses
 * one name with a different type or sensitivity, so a name can never silently mean two different
 * things.
 *
 * @param <T> the variable's runtime value type
 */
public final class WorkflowVariable<T> {

    private final String name;
    private final Class<T> type;
    private final boolean secret;

    private WorkflowVariable(String name, Class<T> type, boolean secret) {
        this.name = name;
        this.type = type;
        this.secret = secret;
    }

    /** Declares a non-secret variable of the given name and runtime type. */
    public static <T> WorkflowVariable<T> publicValue(String name, Class<T> type) {
        return new WorkflowVariable<>(
                requireName(name), Objects.requireNonNull(type, "type"), false);
    }

    /**
     * Declares a secret, {@code String}-valued variable of the given name.
     *
     * <p>Secret variables are deliberately restricted to {@code String} values: that keeps the
     * masking/redaction contract precise, since an arbitrary object's own {@code toString()} cannot
     * be trusted to render safely (see {@code docs/workflow.md#secret-masking}).
     */
    public static WorkflowVariable<String> secret(String name) {
        return new WorkflowVariable<>(requireName(name), String.class, true);
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name cannot be blank");
        }
        return name;
    }

    /** Returns the logical variable name. */
    public String name() {
        return name;
    }

    /** Returns the declared runtime type used for value validation. */
    public Class<T> type() {
        return type;
    }

    /** Returns whether this variable's value must be masked in every incidental rendering. */
    public boolean secret() {
        return secret;
    }

    /** Validates that {@code value} is non-null and assignable to {@link #type()}. */
    void requireValid(Object value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "variable '" + name + "' does not accept a null value");
        }
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException(
                    "variable '"
                            + name
                            + "' requires a value of type "
                            + type.getName()
                            + ", was "
                            + value.getClass().getName());
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof WorkflowVariable<?> other)) {
            return false;
        }
        return secret == other.secret && name.equals(other.name) && type.equals(other.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, secret);
    }

    /** Renders the variable's name, type, and sensitivity - never a value. */
    @Override
    public String toString() {
        return "WorkflowVariable[name="
                + name
                + ", type="
                + type.getSimpleName()
                + (secret ? ", secret]" : "]");
    }
}
