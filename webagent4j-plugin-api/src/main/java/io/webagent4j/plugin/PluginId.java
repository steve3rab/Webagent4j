package io.webagent4j.plugin;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable, non-sensitive textual identity for a plugin. */
public record PluginId(String value) {

    private static final int MAX_LENGTH = 128;
    private static final Pattern VALID_VALUE = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");

    /** Validates the identity without trimming, case folding, or other normalization. */
    public PluginId {
        Objects.requireNonNull(value, "value");
        if (value.length() > MAX_LENGTH || !VALID_VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid plugin id");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
