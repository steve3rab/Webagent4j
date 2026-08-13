package io.webagent4j.observation.internal;

import io.webagent4j.observation.PageMetadata;
import io.webagent4j.observation.spi.PageSnapshot;
import java.time.Clock;
import java.time.Instant;

/** Builds immutable page metadata using the engine's injected clock. */
public final class PageMetadataObserver {

    public PageMetadata observe(PageSnapshot snapshot, Clock clock) {
        Instant capturedAt = clock.instant();
        return new PageMetadata(
                snapshot.url(),
                snapshot.title(),
                snapshot.language(),
                snapshot.charset(),
                snapshot.readyState(),
                capturedAt,
                snapshot.viewport(),
                snapshot.canonicalUrl(),
                snapshot.description());
    }
}
