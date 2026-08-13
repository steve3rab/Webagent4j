package io.webagent4j.verification;

/** A deterministic, side-effect-free condition evaluated against current page state. */
public interface IVerification {

    /**
     * Evaluates the condition and returns structured diagnostics instead of throwing on mismatch.
     */
    VerificationResult verify(IVerificationContext context);
}
