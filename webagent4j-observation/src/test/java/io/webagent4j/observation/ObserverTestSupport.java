package io.webagent4j.observation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.webagent4j.browser.IPage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

final class ObserverTestSupport {

    private ObserverTestSupport() {}

    static Observation observeRich() {
        IPage page = mock(IPage.class);
        when(page.captureObservation(any())).thenReturn(ObservationSnapshotFixtures.richSnapshot());
        return new ObservationEngine(
                        Clock.fixed(Instant.parse("2026-08-13T10:15:30Z"), ZoneOffset.UTC))
                .observe(page, ObservationOptions.builder().includeInputValues(true).build());
    }
}
