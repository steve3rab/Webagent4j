package io.webagent4j.verification;

/** A deterministic, side-effect-free condition evaluated against current page state. */
public interface IVerification {

    /** Returns the stable category used in diagnostics and audit data. */
    default VerificationType type() {
        return VerificationType.CUSTOM;
    }

    /**
     * Evaluates the condition and returns structured diagnostics instead of throwing on mismatch.
     */
    VerificationResult verify(IVerificationContext context);
}
