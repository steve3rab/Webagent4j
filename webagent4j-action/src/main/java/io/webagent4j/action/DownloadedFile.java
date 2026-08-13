package io.webagent4j.action;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Immutable metadata for a completed download owned by the caller. */
public record DownloadedFile(
        String suggestedFilename, Path savedPath, long size, Optional<String> contentType) {

    /** Validates completed download metadata. */
    public DownloadedFile {
        suggestedFilename = Objects.requireNonNull(suggestedFilename, "suggestedFilename");
        Objects.requireNonNull(savedPath, "savedPath");
        if (size < 0) {
            throw new IllegalArgumentException("size cannot be negative");
        }
        contentType = Objects.requireNonNull(contentType, "contentType");
    }
}
