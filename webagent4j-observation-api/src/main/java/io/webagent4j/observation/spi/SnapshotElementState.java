package io.webagent4j.observation.spi;

import io.webagent4j.dom.ElementState;
import java.util.Objects;
import java.util.Optional;

/** Backend-neutral immutable state DTO used only at the observation SPI boundary. */
public record SnapshotElementState(
        ElementState interaction, boolean required, Optional<Boolean> expanded) {

    /** Validates state data. */
    public SnapshotElementState {
        Objects.requireNonNull(interaction, "interaction");
        Objects.requireNonNull(expanded, "expanded");
    }
}
