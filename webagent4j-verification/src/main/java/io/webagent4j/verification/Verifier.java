package io.webagent4j.verification;

import java.util.List;
import java.util.Objects;

/** Stateless default verifier. Instances are thread-safe when supplied contexts are thread-safe. */
public final class Verifier implements IVerifier {

    @Override
    public List<VerificationResult> verifyAll(
            IVerificationContext context, List<? extends IVerification> verifications) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(verifications, "verifications");
        return verifications.stream().map(verification -> verification.verify(context)).toList();
    }
}
