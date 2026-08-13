package io.webagent4j.verification;

/** Minimal page state exposed to deterministic verifications. */
public interface IVerificationContext {

    /** Returns the current page URL. */
    String url();

    /** Returns the current page title. */
    String title();
}
