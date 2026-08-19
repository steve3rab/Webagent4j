package io.webagent4j.wait;

/** Terminal outcome of a {@link WaitEngine#await(WaitBudget, WaitPolicy, IWaitProbe)} call. */
public enum WaitStatus {

    /** The probe reported a satisfied sample, stable for as long as the policy required. */
    SUCCESS,

    /** The budget expired before a satisfied, sufficiently stable sample was observed. */
    TIMED_OUT
}
