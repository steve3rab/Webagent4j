package io.webagent4j.verification;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Evaluates ordered deterministic conditions through one centralized poller. */
public final class VerificationEngine {

    private final VerificationPoller poller;

    /** Creates an engine with the default poller. */
    public VerificationEngine() {
        this(new VerificationPoller());
    }

    /** Creates an engine using an explicit poller. */
    public VerificationEngine(VerificationPoller poller) {
        this.poller = Objects.requireNonNull(poller, "poller");
    }

    /** Evaluates all conditions in encounter order, polling each independently. */
    public List<VerificationResult> awaitAll(
            IVerificationContext context,
            List<? extends IVerification> verifications,
            Duration timeout,
            Duration interval) {
        Objects.requireNonNull(verifications, "verifications");
        List<VerificationResult> results = new ArrayList<>(verifications.size());
        for (IVerification verification : verifications) {
            results.add(poller.await(verification, context, timeout, interval));
        }
        return List.copyOf(results);
    }
}
