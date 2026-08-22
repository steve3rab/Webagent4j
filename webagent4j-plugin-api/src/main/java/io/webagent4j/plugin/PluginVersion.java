package io.webagent4j.plugin;

import java.util.Objects;
import java.util.regex.Pattern;

/** Validated, non-sensitive plugin version metadata with no resolution semantics. */
public record PluginVersion(String value) {

    private static final int MAX_LENGTH = 64;
    private static final Pattern VALID_VALUE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*");

    /** Validates the opaque label without interpreting or normalizing it. */
    public PluginVersion {
        Objects.requireNonNull(value, "value");
        if (value.length() > MAX_LENGTH || !VALID_VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid plugin version");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
