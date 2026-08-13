package io.webagent4j.verification;

import java.util.List;

/** Executes condition objects and preserves their structured results. */
public interface IVerifier {

    /** Evaluates all conditions in encounter order. */
    List<VerificationResult> verifyAll(
            IVerificationContext context, List<? extends IVerification> verifications);
}
