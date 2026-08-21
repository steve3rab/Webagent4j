package io.webagent4j.plugin;

import java.util.Objects;

/** Fail-closed result of an explicit plugin discovery attempt. */
public final class PluginLoadException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final transient PluginLoadFailure failure;

    /** Creates an exception containing only a safe framework-owned diagnostic. */
    public PluginLoadException(PluginLoadFailure failure) {
        super(Objects.requireNonNull(failure, "failure").safeMessage());
        this.failure = failure;
    }

    /** Returns the structured load failure. */
    public PluginLoadFailure failure() {
        return failure;
    }
}
